

package com.hou33.qqsignauto;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-side check of the parsing the module cannot do without. It compiles the real
 * com/hou33/qqsignauto/Card.java (that class has no Android or Xposed dependency) instead of a
 * copy, so there is nothing to drift out of sync, and feeds it the verbatim SignInMainPage
 * answer captured off the phone at 20:08. It shares Card's package so the helpers under test
 * can stay package-private.
 *
 *   cd auto/test
 *   javac -encoding UTF-8 -d . ../src/com/hou33/qqsignauto/Card.java LogicTest.java
 *   java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 com.hou33.qqsignauto.LogicTest
 */


public class LogicTest {

    static int fails = 0;

    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + " -> " + got
                + (ok ? "" : " (wanted " + want + ")"));
        if (!ok) {
            fails++;
        }
    }

    static String fixture() throws Exception {
        java.io.File f = new java.io.File("testdata/mainpage.json");
        if (!f.isFile()) {
            return null;
        }
        java.io.InputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        // --- the two regex/time helpers Card exposes --------------------------------
        eq("retCode parses", Card.firstInt("{\"data\":{\"retCode\":0,\"x\":1}}", "retCode"),
                Integer.valueOf(0));
        eq("no space after colon", Card.firstInt("{\"a\":{\"retCode\":-1}}", "retCode"),
                Integer.valueOf(-1));
        eq("inspireType must not match type",
                Card.firstInt("{\"inspireType\":7}", "type"), null);
        eq("negative sign", Card.firstInt("{\"retCode\":-20503}", "retCode"),
                Integer.valueOf(-20503));
        // isToday has to be tested against a moving "today": the captured stamp 1792752819 is
        // 18:53:39 of 2026-10-23, so it stopped being today the moment the date rolled over.
        // Same clock time, but anchored to the day the test runs on (Asia/Shanghai, +08:00).
        long now = System.currentTimeMillis() / 1000L;
        long midnight = now + 28800L - ((now + 28800L) % 86400L) - 28800L;
        long at1853 = midnight + 18 * 3600L + 53 * 60L;
        eq("18:53 today is today", Card.isToday(at1853), Boolean.TRUE);
        eq("epoch 0 is not today", Card.isToday(0L), Boolean.FALSE);
        eq("yesterday is not today", Card.isToday(at1853 - 86400L), Boolean.FALSE);
        eq("01:00 today is today", Card.isToday(midnight + 3600L), Boolean.TRUE);
        eq("tomorrow 18:53 is not today", Card.isToday(at1853 + 86400L), Boolean.FALSE);

        // --- Card.parse on hand-written shapes --------------------------------------
        String tiny = "{\"data\":{\"retCode\":0,\"vecSignInfo\":{\"value\":["
                + "{\"type\":1,\"subType\":0,\"signInResult\":1,\"signInCover\":{\"title\":\"A\"}},"
                + "{\"type\":2,\"subType\":0,\"signInResult\":0,\"signInCover\":{\"title\":\"B\"}}"
                + "]}}}";
        List<Card> two = Card.parse(tiny);
        eq("two entries", Integer.valueOf(two.size()), Integer.valueOf(2));
        eq("first is done", Boolean.valueOf(two.get(0).done()), Boolean.TRUE);
        eq("second is open", Boolean.valueOf(two.get(1).done()), Boolean.FALSE);
        eq("second is type 2", Integer.valueOf(two.get(1).type), Integer.valueOf(2));
        eq("empty array", Integer.valueOf(Card.parse(
                "{\"vecSignInfo\":{\"value\":[]}}").size()), Integer.valueOf(0));
        eq("no vecSignInfo at all", Integer.valueOf(Card.parse("{\"ret\":0}").size()),
                Integer.valueOf(0));
        // A title containing } and " must not throw the brace counter off.
        String tricky = "{\"vecSignInfo\":{\"value\":[{\"type\":3,\"subType\":1,"
                + "\"signInResult\":0,\"signInCover\":{\"title\":\"有}\\\"花括号\"},"
                + "\"x\":{\"y\":1}}]}}";
        List<Card> trickyCards = Card.parse(tricky);
        eq("string-aware scan", Integer.valueOf(trickyCards.size()), Integer.valueOf(1));
        eq("tricky subType", Integer.valueOf(trickyCards.get(0).subType), Integer.valueOf(1));

        // --- Card.parse on the real capture -----------------------------------------
        // testdata/mainpage.json is the verbatim 20:1x answer: 日签卡 done, 晚安卡 open,
        // and a third card whose subType is an activity id (219834), not a small enum.
        String real = fixture();
        if (real == null) {
            System.out.println("SKIP  real fixture missing (testdata/mainpage.json)");
        } else {
            List<Card> cards = Card.parse(real);
            eq("real card count", Integer.valueOf(cards.size()), Integer.valueOf(3));
            if (cards.size() == 3) {
                eq("real[0] key", cards.get(0).key(), "1/0");
                eq("real[0] done", Boolean.valueOf(cards.get(0).done()), Boolean.TRUE);
                eq("real[1] key", cards.get(1).key(), "2/0");
                eq("real[1] still open", Boolean.valueOf(cards.get(1).done()), Boolean.FALSE);
                eq("real[2] keeps a big subType", cards.get(2).key(), "4/219834");
                eq("real[2] done", Boolean.valueOf(cards.get(2).done()), Boolean.TRUE);
                System.out.println("      real cards: " + cards.get(0) + " | " + cards.get(1)
                        + " | " + cards.get(2));
            }
        }

        // --- the cookie uin extractor (same regex as SignInClient) -------------------
        String jar = "idt=A00003lLKM7T0%2BXt8%2BnF0XgJjFt5gJdN; uin=o2377506767; "
                + "skey=@A1bCdEfGhIjKlMnOpQ; p_uin=o2377506767; p_uid=U002075f003; "
                + "p_skey=AaBbCcDdEeFfGg; a2=014lPzD0Tn4q0Fq0CpQ9";
        eq("uin out of a full jar", uin(jar), "2377506767");
        eq("uin when it comes first", uin("uin=o2377506767; skey=x"), "2377506767");
        eq("p_uin is not uin", uin("p_uin=o2377506767; skey=abc"), null);
        eq("null jar", uin(null), null);

        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILURE(S)");
        if (fails > 0) {
            System.exit(1);
        }
    }

    static String uin(String c) {
        if (c == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?:^|;\\s*)uin=([^;]+)").matcher(c);
        if (!m.find()) {
            return null;
        }
        String u = m.group(1).replaceAll("[^0-9]", "");
        return u.length() >= 5 ? u : null;
    }
}
