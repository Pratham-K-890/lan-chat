package com.lanchat.client.ui;

/**
 * Console UI placeholder — not used in the Android APK.
 *
 * All UI logic for Android lives in:
 *   android-client/app/src/main/java/com/lanchat/android/ui/
 *
 * This class exists so the Maven client module compiles cleanly.
 * It will be deleted once the Android project is wired up.
 */
public class ConsoleUI {

    public static void printInfo(String msg)    { System.out.println("[INFO]  " + msg); }
    public static void printError(String msg)   { System.out.println("[ERROR] " + msg); }
    public static void printMessage(String msg) { System.out.println("[MSG]   " + msg); }
}
