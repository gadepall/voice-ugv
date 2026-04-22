#include <Arduino.h>
#include <DabbleESP32.h>

#define motor_input1 26
#define motor_input2 25
#define motor_input3 33
#define motor_input4 32

void motorControl(int a, int b, int c, int d)
{
  digitalWrite(motor_input1, a); 
  digitalWrite(motor_input2, b); 
  digitalWrite(motor_input3, c); 
  digitalWrite(motor_input4, d); 
}

void setup() 
{
   Serial.begin(115200);

   pinMode(motor_input1, OUTPUT);  
   pinMode(motor_input2, OUTPUT);
   pinMode(motor_input3, OUTPUT);
   pinMode(motor_input4, OUTPUT);

   Dabble.begin("ESP32");   // Bluetooth name
}

void loop() {
  Dabble.processInput();

  if (GamePad.isPressed(0)) {   // forward
    Serial.println("forward");
    motorControl(1, 0, 1, 0);
  }
  else if (GamePad.isPressed(1)) {   // back
    Serial.println("back");
    motorControl(0, 1, 0, 1);
  }
  else if (GamePad.isPressed(3)) {   // left
    Serial.println("left");
    motorControl(1, 0, 0, 1);
  }
  else if (GamePad.isPressed(2)) {   // right
    Serial.println("right");
    motorControl(0, 1, 1, 0);
  }
  else {   // stop if no button is pressed
    motorControl(0, 0, 0, 0);
  }
}

