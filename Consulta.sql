USE iot_project;
CREATE TABLE sensors (
    id INT AUTO_INCREMENT PRIMARY KEY, -- ID único del dato
    sensorId VARCHAR(50) NOT NULL,    -- ID del dispositivo físico (ej: "S1")
    timestamp BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    value DOUBLE NOT NULL,
    idGroup INT NOT NULL
);

CREATE TABLE actuators (
    id INT AUTO_INCREMENT PRIMARY KEY, -- ID único del dato
    actuatorId VARCHAR(50) NOT NULL,  -- ID del dispositivo físico (ej: "A1")
    type VARCHAR(50) NOT NULL,
    status DOUBLE NOT NULL,
    idGroup INT NOT NULL
);
CREATE TABLE IF NOT EXISTS defrost_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    parkId VARCHAR(50),
    sensorId VARCHAR(50),
    startTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alarms (
    id INT AUTO_INCREMENT PRIMARY KEY,
    parkId VARCHAR(50),
    sensorId VARCHAR(50),
    reason VARCHAR(100),
    triggerTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);