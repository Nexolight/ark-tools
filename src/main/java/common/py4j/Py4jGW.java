import py4j.GatewayServer;

public class Py4jGW {
	public static void main(String[] args) {
    		GatewayServer server = new GatewayServer(new Py4jGW());
		server.start();
	}
}
