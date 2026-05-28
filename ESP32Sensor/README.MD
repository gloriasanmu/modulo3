# Lab 06: Hola Mundo Físico - Conectando "The Edge" con Vert.x

## Introducción
En este segundo bloque de la asignatura damos el salto del software puro al mundo ciber-físico. Implementaremos una arquitectura distribuida donde un dispositivo con recursos limitados (ESP32 actuando como *The Edge*) leerá datos de su entorno y los enviará a nuestro servidor central (*The Brain* en Vert.x). 

Dado que todavía no hemos profundizado en el diseño de APIs REST con Vert.x, **el código del servidor se os proporciona ya implementado al final de este documento**. Vuestro objetivo en esta sesión será dominar el hardware, configurar el firmware paso a paso en C/C++ y garantizar que la telemetría cruza la red correctamente hasta llegar al servidor.

## Material Necesario
- 1x Placa microcontroladora ESP32.
- 1x Sensor digital de temperatura y humedad DHT11.
- Cables Dupont.
- Entorno de desarrollo: Visual Studio Code con la extensión **PlatformIO**.

---

## Paso 1: Montaje del Hardware (Electrónica Básica)
Con la placa **desconectada** del cable USB (sin corriente), realizad las siguientes conexiones físicas entre el sensor DHT11 y el ESP32:
1. **Pin VCC (o +) del DHT11** -> Conectadlo al pin **3V3** del ESP32. *(¡Atención! No usar el pin de 5V o VIN, podríais dañar el sensor de forma permanente).*
2. **Pin GND (o -) del DHT11** -> Conectadlo a cualquier pin **GND** del ESP32.
3. **Pin DATA (o OUT) del DHT11** -> Conectadlo al pin digital **4** (D4) del ESP32.

---

## Paso 2: Configuración del Entorno (PlatformIO)
1. Abrid VS Code y cread un nuevo proyecto desde el menú principal de PlatformIO.
2. Seleccionad vuestra placa específica (generalmente `Espressif ESP32 Dev Module`) y marcad el framework `Arduino`.
3. Abrid el archivo de configuración `platformio.ini` y añadid el siguiente bloque de dependencias bajo vuestro entorno para descargar las librerías profesionales que usaremos:

```ini
lib_deps =
    adafruit/DHT sensor library @ ^1.4.4
    adafruit/Adafruit Unified Sensor @ ^1.1.9
    bblanchon/ArduinoJson @ ^6.21.2
```

---

## Paso 3: Programando "The Edge" paso a paso

A continuación, vamos a construir el código de vuestro `src/main.cpp`. Borrad lo que haya generado PlatformIO y copiad este **esqueleto básico**. A partir de aquí, seréis vosotros quienes completéis la lógica siguiendo las instrucciones.

### 3.1. El Esqueleto Básico (Bare minimum)
Copia esto al inicio de tu archivo:

```cpp
#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include "DHT.h"

// TODO 1: Cambia estos valores por los de la red del laboratorio
#define WIFI_SSID "Red_Laboratorio"
#define WIFI_PASSWORD "Password_Secreta"
// TODO 2: Cambia la IP por la IP local de tu ordenador donde corre Vert.x
#define SERVER_URL "http://192.168.1.X:8080/api/sensores"

#define DHTPIN 4       // Pin al que hemos conectado el cable de datos
#define DHTTYPE DHT11  // Tipo de sensor

DHT dht(DHTPIN, DHTTYPE); // Instancia del sensor

void setup() {
  // Aquí escribiremos la configuración inicial
}

void loop() {
  // Aquí escribiremos la lógica que se repite infinitamente
  delay(5000); // Retardo para no saturar el servidor (DDoS)
}
```

### 3.2. Implementando el `setup()` (Inicialización)
Dentro de la función `setup()`, debes escribir el código para cumplir tres objetivos que solo se ejecutan al encender la placa:
1. **Iniciar el puerto serie** a 115200 baudios usando `Serial.begin(115200);` para poder ver los logs en el PC.
2. **Arrancar el sensor** usando el método `.begin()` de la variable `dht`.
3. **Conectar al WiFi:** 
   - Pon la placa en modo estación con `WiFi.mode(WIFI_STA);`.
   - Inicia la conexión con `WiFi.begin(WIFI_SSID, WIFI_PASSWORD);`.
   - Crea un bucle `while (WiFi.status() != WL_CONNECTED)` que imprima puntos por consola (`Serial.print(".");`) y haga un `delay(500);` mientras espera a conectarse.
   - Cuando salga del bucle, imprime un mensaje de éxito mostrando la IP que te han asignado mediante `WiFi.localIP()`.

### 3.3. Implementando el `loop()`: Fase 1 (Lectura)
Dentro del `loop()`, lo primero es interactuar con el mundo físico:
1. Usa `dht.readTemperature();` y guárdalo en una variable `float temp`.
2. Comprueba si la lectura ha fallado usando `if (isnan(temp))`. Si falla, imprime un error por Serial y usa `return;` para abortar esa iteración del bucle.
3. Si la lectura es correcta, imprímela por pantalla.

### 3.4. Implementando el `loop()`: Fase 2 (Serialización JSON)
No podemos mandar el "float" crudo por la red por incompatibilidad de arquitecturas. Empaquétalo en un JSON:
1. Crea un documento estático en la memoria Stack (es lo más seguro para microcontroladores): `StaticJsonDocument<200> doc;`.
2. Añade datos al diccionario como si fuera un mapa. Ejemplo: `doc["sensorId"] = "dht11_01";` y añade tu variable `temp`. Añade también un `timestamp` usando la función `millis()`.
3. Crea un `String payload;` vacío.
4. Serializa el documento hacia ese string usando la función: `serializeJson(doc, payload);`.
5. Imprime el `payload` por Serial para verificar visualmente que tu JSON está bien formado antes de enviarlo.

### 3.5. Implementando el `loop()`: Fase 3 (Envío HTTP)
Si estamos conectados al WiFi (`if (WiFi.status() == WL_CONNECTED)`), envía el dato a Vert.x:
1. Instancia un cliente HTTP: `HTTPClient http;`.
2. Configura el destino: `http.begin(SERVER_URL);`.
3. Añade la cabecera indicando qué tipo de datos envías: `http.addHeader("Content-Type", "application/json");`.
4. Dispara el verbo adecuado (POST): `int responseCode = http.POST(payload);`.
5. Imprime el `responseCode` por la consola. Un 200 o 201 significará éxito. Un valor negativo (como -1) significa que el ESP32 no encuentra la IP de tu PC.
6. Libera la memoria de red cerrando la conexión: `http.end();`.

*(Recuerda dejar el `delay(5000);` al final del bucle).*

---

## Paso 4: El Cerebro (Servidor Vert.x)
Mientras que un compañero termina de escribir el código C++, el otro miembro del equipo debe preparar el servidor.

Cread una clase llamada `SensorApiVerticle` en vuestro proyecto de IntelliJ (Java) y copiad exactamente este código. Levanta un servidor HTTP en el puerto `8080` que escuchará los eventos POST de vuestra placa.

```java
package es.us.dad.vertx;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

public class SensorApiVerticle extends AbstractVerticle {

    @Override
    public void start(Promise<Void> startPromise) {
        
        // 1. Crear el enrutador para nuestra API REST
        Router router = Router.router(vertx);

        // 2. Necesario para que Vert.x procese el cuerpo (Body) de las peticiones POST
        router.route().handler(BodyHandler.create());

        // 3. Definir el Endpoint para recibir la telemetría del ESP32
        router.post("/api/sensores").handler(ctx -> {
            try {
                // Extraer el JSON que nos envía la placa C++
                JsonObject telemetria = ctx.getBodyAsJson();
                
                // Imprimir en la consola del PC
                System.out.println("=========================================");
                System.out.println("📡 ¡Nueva Telemetría Física Recibida!");
                System.out.println("Sensor ID: " + telemetria.getString("sensorId"));
                System.out.println("JSON Completo: " + telemetria.encodePrettily());
                System.out.println("=========================================\n");

                // Responder al ESP32 confirmando la recepción (201 Created)
                ctx.response()
                   .setStatusCode(201)
                   .putHeader("content-type", "application/json")
                   .end(new JsonObject().put("status", "ok").encode());
                   
            } catch (Exception e) {
                System.err.println("Error procesando JSON: " + e.getMessage());
                ctx.response().setStatusCode(400).end("Bad Request");
            }
        });

        // 4. Arrancar el servidor HTTP en el puerto 8080
        vertx.createHttpServer()
             .requestHandler(router)
             .listen(8080, http -> {
                 if (http.succeeded()) {
                     System.out.println("✅ API REST escuchando en el puerto 8080");
                     startPromise.complete();
                 } else {
                     startPromise.fail(http.cause());
                 }
             });
    }
}
```
*(No olvidéis desplegar este Verticle desde vuestro `Launcher` o `Main` y tener añadida la dependencia de `vertx-web` en vuestro `pom.xml`).*

---

## Paso 5: Despliegue y Pruebas End-to-End
1. **Arrancad el servidor Java** en IntelliJ. Mirad la consola y aseguraos de que no hay errores de firewall. Obtened la IP de vuestro ordenador en la WiFi del laboratorio (ej. `192.168.1.X`).
2. Introducid esa IP en el código del ESP32 (`SERVER_URL`).
3. Conectad el ESP32 por USB, compilar y subid el firmware desde PlatformIO (icono de flecha "Upload").
4. Abrid el **Serial Monitor** de PlatformIO (icono de enchufe en la barra inferior) configurado a 115200 baudios.
5. El ESP32 debería conectarse a WiFi, leer el sensor, imprimir el JSON generado e imprimir el Código HTTP `201`.
6. En la consola de IntelliJ, ¡deberíais ver la temperatura física de la sala en tiempo real!