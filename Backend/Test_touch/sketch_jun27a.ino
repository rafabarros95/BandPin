#include <Wire.h>
#include <Trill.h>

Trill trillSensor;

void setup() {
  Serial.begin(115200);
  delay(1000);

  Wire.begin(21, 22);

  Serial.println("Starting flex-print-raw...");

  int ret;
  while ((ret = trillSensor.setup(Trill::TRILL_FLEX, 0x48))) {
    Serial.println("failed to initialise trillSensor");
    Serial.print("Error code: ");
    Serial.println(ret);
    delay(500);
  }

  Serial.println("Trill Flex connected");

  trillSensor.setPrescaler(3);
  delay(10);

  trillSensor.setNoiseThreshold(200);
  delay(10);

  trillSensor.updateBaseline();
  delay(100);

  Serial.println("Ready. Touch the strip.");
}

void loop() {
  delay(100);

  trillSensor.requestRawData();

  Serial.print("RAW: ");

  while (trillSensor.rawDataAvailable() > 0) {
    int data = trillSensor.rawDataRead();

    if (data < 1000) Serial.print(0);
    if (data < 100) Serial.print(0);
    if (data < 10) Serial.print(0);

    Serial.print(data);
    Serial.print(" ");
  }

  Serial.println();
}


