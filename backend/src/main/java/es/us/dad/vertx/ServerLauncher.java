package es.us.dad.vertx;

import io.vertx.core.Vertx;

public class ServerLauncher {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        try {
            vertx.deployVerticle(new SensorApiVerticle()).onComplete(res -> {
                if (res.succeeded()) {
                    System.out.println("✅ MainVerticle desplegado ID: " + res.result());
                } else {
                    System.err.println("❌ Fallo en despliegue: " + res.cause());
                    vertx.close(); // Cerrar si falla
                }
            });

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}