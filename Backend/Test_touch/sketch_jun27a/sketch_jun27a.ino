#include <Wire.h>    // I2C library
#include <Trill.h>  

Trill trillSensor;  

void setup() {
  Serial.begin(115200); // Start serial communication
  delay(1000);          

  Wire.begin(21, 22);   // Start I2C (SDA=21, SCL=22)
}

  Serial.println("Starting flex-print-raw...");

  int ret;
  while ((ret = trillSensor.setup(Trill::TRILL_FLEX, 0x48))) { // // Try to initialize the sensor
    Serial.println("failed to initialise trillSensor");
    Serial.print("Error code: ");
    Serial.println(ret);
    delay(500);
  }

  Serial.println("Trill Flex connected");

  trillSensor.setPrescaler(3); // Set sampling speed
  delay(10);

  trillSensor.setNoiseThreshold(200); // Set noise threshold
  delay(10);

  trillSensor.updateBaseline(); // Calibrate baseline
  delay(100);

  Serial.println("Ready. Touch the strip.");
}

void loop() {
  delay(100);

  trillSensor.requestRawData(); // Request raw sensor data

  Serial.print("RAW: ");

  while (trillSensor.rawDataAvailable() > 0) { // Read all available data
    int data = trillSensor.rawDataRead();  // Read one raw value

    if (data < 1000) Serial.print(0);
    if (data < 100) Serial.print(0);
    if (data < 10) Serial.print(0);

    Serial.print(data);
    Serial.print(" ");
  }

  Serial.println();
}


