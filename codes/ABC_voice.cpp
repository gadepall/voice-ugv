#include <Arduino.h>
#include "BluetoothSerial.h"

#define motor_input1 26
#define motor_input2 25
#define motor_input3 33
#define motor_input4 32

BluetoothSerial SerialBT;  // Create Bluetooth Serial object

void motorControl(int a, int b, int c, int d)
{
  digitalWrite(motor_input1, a); 
  digitalWrite(motor_input2, b); 
  digitalWrite(motor_input3, c); 
  digitalWrite(motor_input4, d); 
}

String voice;

void setup() 
{
   Serial.begin(115200);

   pinMode(motor_input1, OUTPUT);  
   pinMode(motor_input2, OUTPUT);
   pinMode(motor_input3, OUTPUT);
   pinMode(motor_input4, OUTPUT);

   SerialBT.begin("ESP32");  // Bluetooth Name
   Serial.println("Bluetooth Voice control ready...");
}

void loop() {
  if (SerialBT.available()) {
    voice = SerialBT.readString();
    voice.trim();   // remove spaces/newlines
    Serial.print("Command: ");
    Serial.println(voice);

    if (voice == "forward") {
      motorControl(1, 0, 1, 0);
      Serial.println("Moving Forward");
    }
    else if (voice == "back") {
      motorControl(0, 1, 0, 1);
      Serial.println("Moving Backward");
    }
    else if (voice == "right") {
      motorControl(1, 0, 0, 1);
      Serial.println("Turning Left");
    }
    else if (voice == "left") {
      motorControl(0, 1, 1, 0);
      Serial.println("Turning Right");
    }
    else if (voice == "stop") {
      motorControl(0, 0, 0, 0);
      Serial.println("Stopped");
    }
    else {
      Serial.println("Invalid Command");
    }
  }
}

