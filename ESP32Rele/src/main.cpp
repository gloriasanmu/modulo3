/**
 * ESP32 RELÉ — Módulo 2.3: Filtro de Desescarche
 *
 * Función: Suscrito al topic MQTT del compresor. Cuando Vert.x
 * detecta una alarma de desescarche publica {"command":"ON"} y
 * este ESP32 activa el relé. Al recibir {"command":"OFF"} lo apaga.
 *
 * Hardware:
 *   Relé VCC (DC+) → VIN/5V (o 3.3V según módulo)
 *   Relé GND (DC-) → GND
 *   Relé IN  (SIG) → GPIO 5
 */

#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// ── Configuración ─────────────────────────────────────────
const char* WIFI_SSID     = "junimo";
const char* WIFI_PASSWORD = "holaholakk";
const char* MQTT_BROKER   = "10.238.31.189";   // IP del PC con Mosquitto
const int   MQTT_PORT     = 1883;

// Identificadores (deben coincidir con los que usa Vert.x)
const char* PARK_ID     = "P1";
const char* ACTUATOR_ID = "A1";
// Topic resultante: park/P1/food/compressor/A1/command
char MQTT_TOPIC_CMD[64];

// ── Hardware ──────────────────────────────────────────────
#define RELAY_PIN 5
// Lógica del módulo relé: HIGH = bobina energizada (compresor ON)
// Si tu módulo es de lógica invertida (active-LOW) cambia los valores
#define RELAY_ON  HIGH
#define RELAY_OFF LOW

// ── Clientes de red ───────────────────────────────────────
WiFiClient   espClient;
PubSubClient mqttClient(espClient);

// ── Prototipos ────────────────────────────────────────────
void connectWiFi();
void connectMQTT();
void mqttCallback(char* topic, byte* payload, unsigned int length);

// ─────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);

    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, RELAY_OFF);  // Seguro por defecto: compresor apagado
    Serial.println("\n⚡ ESP32 Relé iniciando — compresor por defecto: OFF");

    snprintf(MQTT_TOPIC_CMD, sizeof(MQTT_TOPIC_CMD),
             "park/%s/food/compressor/%s/command", PARK_ID, ACTUATOR_ID);
    Serial.printf("   Escuchando topic: %s\n", MQTT_TOPIC_CMD);

    connectWiFi();

    mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
    mqttClient.setCallback(mqttCallback);
}

// ─────────────────────────────────────────────────────────
void loop() {
    if (WiFi.status() != WL_CONNECTED) connectWiFi();
    if (!mqttClient.connected())        connectMQTT();
    mqttClient.loop();  // Mantiene conexión y procesa mensajes entrantes
    // Sin delay(): el ESP32 nunca está bloqueado, siempre listo para recibir
}

// ─────────────────────────────────────────────────────────
/**
 * Callback invocado por PubSubClient al recibir un mensaje MQTT.
 * Payload esperado: {"command":"ON"} o {"command":"OFF"}
 */
void mqttCallback(char* topic, byte* payload, unsigned int length) {
    Serial.printf("\n📩 Comando MQTT en [%s]\n", topic);

    // Deserializar el payload (array de bytes → JsonDocument)
    StaticJsonDocument<256> doc;
    DeserializationError error = deserializeJson(doc, payload, length);

    if (error) {
        Serial.printf("   ❌ JSON inválido: %s\n", error.c_str());
        return;
    }

    const char* command = doc["command"];
    if (command == nullptr) {
        Serial.println("   ❌ Campo 'command' ausente en el JSON");
        return;
    }

    Serial.printf("   🔑 command = \"%s\"\n", command);

    if (String(command) == "ON") {
        digitalWrite(RELAY_PIN, RELAY_ON);
        Serial.println("   ✅ Relé ACTIVADO — Compresor encendido 🟢");
    } else if (String(command) == "OFF") {
        digitalWrite(RELAY_PIN, RELAY_OFF);
        Serial.println("   ✅ Relé DESACTIVADO — Compresor apagado 🔴");
    } else {
        Serial.printf("   ⚠️  Comando desconocido: %s\n", command);
    }
}

// ─────────────────────────────────────────────────────────
void connectWiFi() {
    Serial.print("📶 Conectando a WiFi...");
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\n✅ WiFi conectado. IP: %s\n", WiFi.localIP().toString().c_str());
}

void connectMQTT() {
    while (!mqttClient.connected()) {
        Serial.print("🔌 Conectando a Broker MQTT...");
        String clientId = String("ESP32_Relay_") + ACTUATOR_ID;
        if (mqttClient.connect(clientId.c_str())) {
            Serial.println(" ✅ Conectado");
            // Suscribirse al topic de comandos tras cada reconexión
            mqttClient.subscribe(MQTT_TOPIC_CMD);
            Serial.printf("   📥 Suscrito a: %s\n", MQTT_TOPIC_CMD);
        } else {
            Serial.printf(" ❌ Fallo rc=%d — reintentando en 5s\n", mqttClient.state());
            delay(5000);
        }
    }
}