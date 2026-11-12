// GENERATED FILE - DO NOT EDIT.
// Derived from work/checkin/auto/src/com/hou33/qqsignauto/AutoCard.java by work/checkin/sync_probe.py
// It lets the capture probe run the shipping module's logic and log the result.
package com.hou33.qqsignlog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of the "我的卡片" list the server hands back. QQ currently ships two:
 * 日签卡 (type 1) and 晚安卡 (type 2, opens in the evening). Anything QQ adds later is picked up
 * the same way, which is why nothing here is keyed on a hard-coded type number.
 */
public final class AutoCard {
    public final int type;
    public final int subType;
    public final int result;
    public final String title;

    AutoCard(int type, int subType, int result, String title) {
        this.type = type;
        this.subType = subType;
        this.result = result;
        this.title = title;
    }

    public boolean done() {
        return result != 0;
    }

    public String key() {
        return type + "/" + subType;
    }

    @Override
    public String toString() {
        return title + "(" + key() + ",result=" + result + ")";
    }

    /** Pull every object of data.vecSignInfo.value[] out of a SignInMainPage answer. */
    public static ArrayList<AutoCard> parse(String json) {
        ArrayList<AutoCard> out = new ArrayList<AutoCard>();
        if (json == null) {
            return out;
        }
        int at = json.indexOf("\"vecSignInfo\"");
        if (at < 0) {
            return out;
        }
        int open = json.indexOf('[', at);
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    AutoCard c = one(json.substring(start, i + 1));
                    if (c != null) {
                        out.add(c);
                    }
                    start = -1;
                } else if (depth < 0) {
                    break;
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static AutoCard one(String obj) {
        Integer type = firstInt(obj, "type");
        if (type == null) {
            return null;
        }
        Integer sub = firstInt(obj, "subType");
        Integer res = firstInt(obj, "signInResult");
        Matcher m = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
        String title = m.find() ? m.group(1) : "?";
        return new AutoCard(type.intValue(), sub == null ? 0 : sub.intValue(),
                res == null ? 0 : res.intValue(), title);
    }

    static Integer firstInt(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static boolean isToday(long epochSeconds) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(epochSeconds * 1000L);
        Calendar b = Calendar.getInstance();
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
