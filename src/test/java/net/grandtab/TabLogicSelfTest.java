package net.grandtab;

import net.grandtab.logic.TabLogic;

/** Dependency-free checks of TabLogic. Exits with status 1 on the first failure. */
public final class TabLogicSelfTest {
    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            System.err.println("FAILED: " + what);
            System.exit(1);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        check(expected.equals(actual), what + " (expected " + expected + ", got " + actual + ")");
    }

    public static void main(String[] args) {
        eq(0xFFAA00, TabLogic.parseColor("gold"), "named colour");
        eq(0x55FF55, TabLogic.parseColor(" #55ff55 "), "hex colour, case and spaces");
        boolean threw = false;
        try {
            TabLogic.parseColor("#12345");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "short hex refused");
        eq(0x123456, TabLogic.colorOr("nonsense", 0x123456), "invalid colour falls back");
        eq(0x123456, TabLogic.colorOr("", 0x123456), "empty colour falls back");
        eq("#0A0B0C", TabLogic.formatColor(0x0A0B0C), "colour formatting");

        eq("[K3 WARDEN]", TabLogic.prefix("[{kingdom} {role}]", "K3", "Kingdom III", "WARDEN"), "example prefix");
        eq("Kingdom I - MONARCH", TabLogic.prefix("{kingdom_name} - {role}", "K1", "Kingdom I", "MONARCH"),
                "kingdom_name is not confused with kingdom");

        eq("gt0001", TabLogic.sortTeam(0, 1), "first kingdom, monarch");
        eq("gt0205", TabLogic.sortTeam(2, 5), "third kingdom, knight");
        check(TabLogic.sortTeam(0, 1).compareTo(TabLogic.sortTeam(0, 2)) < 0, "monarch sorts before champion");
        check(TabLogic.sortTeam(0, 5).compareTo(TabLogic.sortTeam(1, 1)) < 0, "kingdom I knight before kingdom II monarch");
        check(TabLogic.sortTeam(2, 5).compareTo(TabLogic.UNRANKED_TEAM) < 0, "unranked players last");
        check(TabLogic.sortTeam(5, 3).length() <= 16, "team names fit the 16-character limit");
        check(TabLogic.isSortTeam("gt0102") && !TabLogic.isSortTeam("fwr0123456789abc") && !TabLogic.isSortTeam("gt01"),
                "only our sorting teams are recognised");

        eq(0x55FF55, TabLogic.pingColor(40), "good ping green");
        eq(0xFFFF55, TabLogic.pingColor(200), "medium ping yellow");
        eq(0xFF5555, TabLogic.pingColor(900), "bad ping red");
        eq(10, TabLogic.rule(10).length(), "rule width");
        eq("ONLINE PLAYERS: 7/15", TabLogic.count("ONLINE PLAYERS: {online}/{max}", 7, 15), "player count");
        check(TabLogic.validId("K1") && TabLogic.validId("monarch") && !TabLogic.validId("bad id"), "ids");

        System.out.println("TabLogicSelfTest: all " + checks + " checks passed");
    }
}
