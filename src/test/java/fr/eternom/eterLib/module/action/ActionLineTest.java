package fr.eternom.eterLib.module.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionLineTest {

    @Test
    void simpleActionWithArguments() {
        ActionLine line = ActionLine.parse("eterjob:quests mineur 2");
        assertNull(line.server());
        assertFalse(line.condition());
        assertEquals("eterjob:quests", line.id());
        assertEquals("eterjob", line.namespace());
        assertEquals(List.of("mineur", "2"), line.args());
    }

    @Test
    void quotedArgumentsKeepSpacesAndEscapedQuotes() {
        ActionLine line = ActionLine.parse("eterlib:message \"Bonjour {player}, \\\"ça va\\\" ?\" fin");
        assertEquals(List.of("Bonjour {player}, \"ça va\" ?", "fin"), line.args());
        assertEquals(List.of("", "x"), ActionLine.parse("a:b \"\" x").args());
    }

    @Test
    void conditionsAndNegation() {
        ActionLine condition = ActionLine.parse("?eterjob:has-job mineur");
        assertTrue(condition.condition());
        assertFalse(condition.negate());
        ActionLine negated = ActionLine.parse("?!eterlib:permission eter.vip");
        assertTrue(negated.condition());
        assertTrue(negated.negate());
        assertEquals("eterlib:permission", negated.id());
    }

    @Test
    void forcedServer() {
        ActionLine line = ActionLine.parse("@survie EterJob:Guild");
        assertEquals("survie", line.server());
        assertEquals("eterjob:guild", line.id());
    }

    @Test
    void invalidLinesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ActionLine.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ActionLine.parse("sans-deux-points"));
        assertThrows(IllegalArgumentException.class, () -> ActionLine.parse("@survie"));
        assertThrows(IllegalArgumentException.class, () -> ActionLine.parse("a:b \"pas fermé"));
    }

    @Test
    void placeholdersAndFormatRoundTrip() {
        ActionLine line = ActionLine.parse("@survie ?!eterlib:message \"Salut {player}\" {uuid}");
        assertEquals(List.of("Salut Nad", "u-1"), line.resolve(Map.of("player", "Nad", "uuid", "u-1")));
        assertEquals(line, ActionLine.parse(line.format()));
    }
}
