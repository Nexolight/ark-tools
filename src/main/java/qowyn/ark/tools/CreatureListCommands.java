package qowyn.ark.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonGenerator;

import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import qowyn.ark.ArkSavegame;
import qowyn.ark.GameObject;
import qowyn.ark.ReadingOptions;

public class CreatureListCommands {

  private static ArkSavegame saveFile;

  private static OptionHandler optionHandler;

  private static Path outputDirectory;

  private static OptionSpec<Void> untameableSpec;

  private static OptionSpec<Void> statisticsSpec;

  private static OptionSpec<Void> withoutIndexSpec;

  private static OptionSpec<Void> cleanFolderSpec;

  private static OptionSpec<Void> writeAllFieldsSpec;

  private static OptionSet options;

  private static Consumer<JsonGenerator> writerFunction;

  public static void creatures(OptionHandler optionHandler) {
    CreatureListCommands.optionHandler = optionHandler;
    listImpl(null);
  }

  public static void tamed(OptionHandler optionHandler) {
    CreatureListCommands.optionHandler = optionHandler;
    listImpl(CommonFunctions::onlyTamed);
  }

  public static void wild(OptionHandler optionHandler) {
    CreatureListCommands.optionHandler = optionHandler;
    listImpl(CommonFunctions::onlyWild);
  }

  protected static boolean neededClasses(GameObject object) {
    return object.getClassString().contains("_Character_") || object.getClassString().startsWith("DinoCharacterStatusComponent_") || object.getClassString().equals("Raft_BP_C");
  }

  protected static boolean onlyTameable(GameObject object) {
    return (!object.hasAnyProperty("bForceDisablingTaming") || !object.getPropertyValue("bForceDisablingTaming", Boolean.class)) || object.getClassString().equals("Raft_BP_C");
  }

  protected static boolean onlyCreatures(GameObject object) {
    return object.getClassString().contains("_Character_") || object.getClassString().equals("Raft_BP_C");
  }

  protected static void listImpl(BiPredicate<GameObject, ArkSavegame> filter) {
    try {
      untameableSpec = optionHandler.accepts("include-untameable", "Include untameable high-level dinos.");
      statisticsSpec = optionHandler.accepts("statistics", "Wrap list of dinos in statistics block.");
      withoutIndexSpec = optionHandler.accepts("without-index", "Omits reading and writing classes.json");
      cleanFolderSpec = optionHandler.accepts("clean", "Deletes all .json files in the target directory.");
      writeAllFieldsSpec = optionHandler.accepts("write-all-fields", "Writes all the fields.");

      options = optionHandler.reparse();

      List<String> params = optionHandler.getParams(options);
      if (params.size() != 2 || optionHandler.wantsHelp()) {
        optionHandler.printCommandHelp();
        System.exit(1);
        return;
      }

      if (!options.has(withoutIndexSpec)) {
        DataManager.loadData(optionHandler.lang());
      }

      String savePath = params.get(0);
      outputDirectory = Paths.get(params.get(1));

      ReadingOptions readingOptions = optionHandler.readingOptions().withObjectFilter(CreatureListCommands::neededClasses);

      Stopwatch stopwatch = new Stopwatch(optionHandler.useStopwatch());
      saveFile = new ArkSavegame(savePath, readingOptions);
      stopwatch.stop("Reading");
      writeAnimalLists(filter);
      stopwatch.stop("Dumping");

      stopwatch.print();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  public static void writeAnimalLists(BiPredicate<GameObject, ArkSavegame> filter) {
    Stream<GameObject> objectStream = saveFile.getObjects().parallelStream().filter(CreatureListCommands::onlyCreatures);

    if (filter != null) {
      objectStream = objectStream.filter(object -> filter.test(object, saveFile));
    }

    if (!options.has(untameableSpec)) {
      objectStream = objectStream.filter(CreatureListCommands::onlyTameable);
    }

    if (options.has(cleanFolderSpec)) {
      try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(outputDirectory, "*.json")) {
        for (Path path : directoryStream) {
          Files.delete(path);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    ConcurrentMap<String, List<GameObject>> dinoLists = objectStream.collect(Collectors.groupingByConcurrent(GameObject::getClassString));

    if (!options.has(withoutIndexSpec)) {
      Map<String, String> classNames = readClassNames();

      Function<String, String> fetchName = key -> DataManager.hasCreature(key) ? DataManager.getCreature(key).getName() : key;
      dinoLists.keySet().forEach(dinoClass -> classNames.computeIfAbsent(dinoClass, fetchName));

      writeClassNames(classNames);

      if (options.has(statisticsSpec)) {
        writerFunction = g -> g.writeStartObject().write("count", 0).writeStartArray("dinos").writeEnd().writeEnd();
      } else {
        writerFunction = g -> g.writeStartArray().writeEnd();
      }

      classNames.keySet().stream().filter(s -> !dinoLists.containsKey(s)).forEach(CreatureListCommands::writeEmpty);
    }

    dinoLists.entrySet().parallelStream().forEach(CreatureListCommands::writeList);
  }

  public static Map<String, String> readClassNames() {
    Path classFile = outputDirectory.resolve("classes.json");
    Map<String, String> classNames = new HashMap<>();

    if (Files.exists(classFile)) {
      try (InputStream classStream = Files.newInputStream(classFile)) {
        JsonReader classReader = Json.createReader(classStream);
        JsonArray classArray = classReader.readArray();
        for (JsonObject o : classArray.getValuesAs(JsonObject.class)) {
          String cls = o.getString("cls");
          String name = o.getString("name");
          if (!classNames.containsKey(cls)) {
            classNames.put(cls, name);
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    return classNames;
  }

  public static void writeClassNames(Map<String, String> classNames) {
    Path classFile = outputDirectory.resolve("classes.json");

    try (OutputStream clsStream = Files.newOutputStream(classFile)) {
      CommonFunctions.writeJson(clsStream, g -> {
        g.writeStartArray();

        classNames.forEach((cls, name) -> g.writeStartObject().write("cls", cls).write("name", name).writeEnd());

        g.writeEnd();
      }, optionHandler);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void writeList(Map.Entry<String, List<GameObject>> entry) {
    Path outputFile = outputDirectory.resolve(entry.getKey() + ".json");

    List<? extends GameObject> filteredClasses = entry.getValue();
    LatLonCalculator latLongCalculator = LatLonCalculator.forSave(saveFile);

    try (OutputStream out = Files.newOutputStream(outputFile)) {
      CommonFunctions.writeJson(out, generator -> {
        if (options.has(statisticsSpec)) {
          generator.writeStartObject();

          generator.write("count", filteredClasses.size());

          IntSummaryStatistics statistics =
              filteredClasses.stream().filter(a -> CommonFunctions.onlyWild(a, saveFile)).mapToInt(a -> CommonFunctions.getBaseLevel(a, saveFile)).summaryStatistics();
          if (statistics.getCount() > 0) {
            generator.write("wildMin", statistics.getMin());
            generator.write("wildMax", statistics.getMax());
            generator.write("wildAverage", statistics.getAverage());
          }

          IntSummaryStatistics tamedBaseStatistics =
              filteredClasses.stream().filter(a -> CommonFunctions.onlyTamed(a, saveFile)).mapToInt(a -> CommonFunctions.getBaseLevel(a, saveFile)).summaryStatistics();
          if (tamedBaseStatistics.getCount() > 0) {
            generator.write("tamedBaseMin", tamedBaseStatistics.getMin());
            generator.write("tamedBaseMax", tamedBaseStatistics.getMax());
            generator.write("tamedBaseAverage", tamedBaseStatistics.getAverage());
          }

          IntSummaryStatistics tamedFullStatistics =
              filteredClasses.stream().filter(a -> CommonFunctions.onlyTamed(a, saveFile)).mapToInt(a -> CommonFunctions.getFullLevel(a, saveFile)).summaryStatistics();
          if (tamedFullStatistics.getCount() > 0) {
            generator.write("tamedFullMin", tamedFullStatistics.getMin());
            generator.write("tamedFullMax", tamedFullStatistics.getMax());
            generator.write("tamedFullAverage", tamedFullStatistics.getAverage());
          }

          generator.writeStartArray("dinos");
        } else {
          generator.writeStartArray();
        }

        for (GameObject creature : filteredClasses) {
          SharedWriters.writeCreatureInfo(generator, creature, latLongCalculator, saveFile, options.has(writeAllFieldsSpec));
        }

        generator.writeEnd(); // Array

        if (options.has(statisticsSpec)) {
          generator.writeEnd(); // Object
        }
      }, optionHandler);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void writeEmpty(String s) {
    Path outputFile = outputDirectory.resolve(s + ".json");

    try (OutputStream out = Files.newOutputStream(outputFile)) {
      CommonFunctions.writeJson(out, writerFunction, optionHandler);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}
