package fr.eternom.eterLib.helper.message;

import java.util.Locale;

/** Nombres lisibles dans les menus et les messages. */
public final class Numbers {

    private Numbers() {
    }

    /**
     * 1234567 -> « 1 234 567 ». Les espaces insécables du format français (U+202F, U+00A0) deviennent des espaces
     * simples : la police de Minecraft ne les a pas.
     */
    public static String format(long value) {
        return String.format(Locale.FRANCE, "%,d", value).replace(' ', ' ').replace(' ', ' ');
    }
}
