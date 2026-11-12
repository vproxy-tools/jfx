/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import static com.sun.glass.events.KeyEvent.*;

/** Godot logical keys to Glass keys; Unicode text travels independently. */
final class GodotKeyMap {
    private GodotKeyMap() {}

    static int glassKey(int key) {
        if ((key & (1 << 22)) == 0) {
            if (key >= 'A' && key <= 'Z' || key >= '0' && key <= '9') return key;
            return switch (key) {
                case ' ' -> VK_SPACE;
                case ',' -> VK_COMMA;
                case '-' -> VK_MINUS;
                case '.' -> VK_PERIOD;
                case '/' -> VK_SLASH;
                case ';' -> VK_SEMICOLON;
                case '=' -> VK_EQUALS;
                case '[' -> VK_OPEN_BRACKET;
                case '\\' -> VK_BACK_SLASH;
                case ']' -> VK_CLOSE_BRACKET;
                case '`' -> VK_BACK_QUOTE;
                case '\'' -> VK_QUOTE;
                default -> VK_UNDEFINED;
            };
        }
        int special = key & ~(1 << 22);
        if (special >= 0x1c && special <= 0x27) return VK_F1 + special - 0x1c;
        if (special >= 0x28 && special <= 0x33) return VK_F13 + special - 0x28;
        if (special >= 0x86 && special <= 0x8f) return VK_NUMPAD0 + special - 0x86;
        return switch (special) {
            case 1 -> VK_ESCAPE;
            case 2, 3 -> VK_TAB;
            case 4 -> VK_BACKSPACE;
            case 5, 6 -> VK_ENTER;
            case 7 -> VK_INSERT;
            case 8 -> VK_DELETE;
            case 9 -> VK_PAUSE;
            case 10 -> VK_PRINTSCREEN;
            case 12 -> VK_CLEAR;
            case 13 -> VK_HOME;
            case 14 -> VK_END;
            case 15 -> VK_LEFT;
            case 16 -> VK_UP;
            case 17 -> VK_RIGHT;
            case 18 -> VK_DOWN;
            case 19 -> VK_PAGE_UP;
            case 20 -> VK_PAGE_DOWN;
            case 21 -> VK_SHIFT;
            case 22 -> VK_CONTROL;
            case 23 -> VK_WINDOWS;
            case 24 -> VK_ALT;
            case 25 -> VK_CAPS_LOCK;
            case 26 -> VK_NUM_LOCK;
            case 27 -> VK_SCROLL_LOCK;
            case 0x42 -> VK_CONTEXT_MENU;
            case 0x45 -> VK_HELP;
            case 0x81 -> VK_MULTIPLY;
            case 0x82 -> VK_DIVIDE;
            case 0x83 -> VK_SUBTRACT;
            case 0x84 -> VK_DECIMAL;
            case 0x85 -> VK_ADD;
            default -> VK_UNDEFINED;
        };
    }
}
