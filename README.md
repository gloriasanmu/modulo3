# modulo3

En este trabajo hemos realizado un proyecto de edge computing siguiéndo la temática indicada; el filtro de desescarche para los congeladores de un parque de atracciones. Para esto hemos empleado dos ESP32, uno que actuaba como sensor de temperatura, y otro que actuaba como relé, el cual simulaba un compresor de comida. El comportamiento de ambos ESP32 viene dictado por un vertice en Vert.x, el cual evalúa los datos suministrados según una lógica interna para decidir si las lecturas son correctas, desescarches normales (sin alarma), o desescarches anómalos (con alarma y activación del compresor). La lógica interna sigue el siguiente esquema:

Llega lectura de temperatura
        │
        ▼
  ¿Temp > -10°C?
    /         \
   SÍ          NO
   │            └─► ¿Hay timer activo para este sensorId?
   │                       │
   │                    SÍ → cancelTimer() → el desescarche fue normal, no pasa nada
   │                    NO → ignorar (todo normal)
   │
   ▼
¿Ya hay timer activo para este sensorId?
    │
   SÍ → no lanzar otro, esperar a que expire
    │
   NO → vertx.setTimer(20 min) y guardar el timerId en el Map
              │
              ▼ (cuando expira el timer)
        ¿Sigue temp > 0°C?
           /        \
          SÍ         NO → desescarche normal, limpiar Map
          │
          ▼
       FALLO CONFIRMADO:
    - Insertar en defrost_logs (BD)
    - Insertar en alarms (BD)
    - Publicar MQTT: {"command":"ON"} → topic del compresor
    - Limpiar entry del Map


Adjunto aquí una imagen del montaje físico de los ESP32:
<img width="4080" height="3072" alt="imagen" src="https://github.com/user-attachments/assets/67e53e28-1442-4050-8747-86a8d7d186a9" />

Los ESP32 se encargan de recoger los datos (sensor) y actuar como compresor (relé), estos datos son enviados a través de MQTT a vert.x, quien recoge los datos y los evalúa. En caso de que la alarma se lance, se guarda una alarma activa en la base de datos (que puede ser borrada a través de un DELETE de POSTMAN), y un log permanente en defrost_logs (que pueden ser revisados todos de una a través de un GET de postman).
Aquí se adjuntan dos imágenes que ilustran dichas conexiones MQTT de los ESP32, las cuales pueden ser accedidas a través de los terminales en serie de los puertos COM:
<img width="874" height="181" alt="imagen" src="https://github.com/user-attachments/assets/e6ceaf4b-0868-46bb-b849-3e8309734b32" />
<img width="500" height="137" alt="imagen" src="https://github.com/user-attachments/assets/7fa21f41-c4d3-4cf7-83f8-ae025b5fcdc6" />
Y aquí, los mensajes MQTT a través del sistema Pub/Sub que hemos implementado en nuestros ESP32, los cuales están siendo mostrados a través de MQTT explorer, donde el host es la IP del ordenador donde se está ejecutando vert.x:
<img width="1268" height="897" alt="imagen" src="https://github.com/user-attachments/assets/75899f98-8536-494b-8766-da297a5bee60" />
Por último, aquí adjuntamos una imágen del aspecto de la base de datos donde Vert.x publicará las alarmas y los defrost logs:
<img width="347" height="83" alt="imagen" src="https://github.com/user-attachments/assets/fefbce34-7792-4add-8792-57878fa5ed7c" />

