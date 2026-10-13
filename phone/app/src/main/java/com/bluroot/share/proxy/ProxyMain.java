package com.bluroot.share.proxy;

import java.io.File;

/** Entry point for the root process. The screen app starts this with app_process. */
public final class ProxyMain {
    public static void main(String[] args) {
        int code = 1;
        try {
            code = new ProxyMain().run(args);
        } catch (Throwable error) {
            error.printStackTrace();
        }
        System.exit(code);
    }

    private int run(String[] args) {
        int port = 8877;
        String token = "";
        String name = "phone";
        String status = "/data/local/tmp/bluroot.status";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equals(arg) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--token".equals(arg) && i + 1 < args.length) {
                token = args[++i];
            } else if ("--name".equals(arg) && i + 1 < args.length) {
                name = args[++i];
            } else if ("--status".equals(arg) && i + 1 < args.length) {
                status = args[++i];
            } else {
                System.err.println("Unknown argument: " + arg);
                return 2;
            }
        }
        if (port < 1 || port > 65535) {
            System.err.println("Port is out of range");
            return 2;
        }
        if (token.isEmpty()) {
            System.err.println("Missing --token");
            return 2;
        }
        new File(ProxyServer.STOP_PATH).delete();
        ProxyServer server = new ProxyServer(port, token, name, new File(status));
        server.run();
        return 0;
    }
}
