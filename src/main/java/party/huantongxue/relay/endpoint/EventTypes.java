package party.huantongxue.relay.endpoint;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import party.huantongxue.relay.common.ApiException;

/**
 * 事件类型与订阅模式。
 *
 * <ul>
 *   <li>事件类型：点分段，如 {@code order.created}</li>
 *   <li>订阅模式：{@code *}（全部）、{@code order.*}（前缀，含多级如 order.item.added）、或精确类型</li>
 * </ul>
 */
public final class EventTypes {

    private static final String SEGMENTS = "[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*";
    private static final Pattern TYPE = Pattern.compile(SEGMENTS);
    private static final Pattern PATTERN = Pattern.compile("\\*|" + SEGMENTS + "(\\.\\*)?");
    private static final int MAX_TYPE_LENGTH = 128;

    private EventTypes() {
    }

    public static void requireValidType(String type) {
        if (type == null || type.length() > MAX_TYPE_LENGTH || !TYPE.matcher(type).matches()) {
            throw ApiException.badRequest("invalid_event_type",
                    "event type must be dot-separated [A-Za-z0-9_-] segments, at most 128 chars");
        }
    }

    /** 校验、去重并保持顺序；结果用逗号拼接后入库。 */
    public static String normalizePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            throw ApiException.badRequest("invalid_event_types", "eventTypes must not be empty");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : patterns) {
            String p = raw == null ? "" : raw.trim();
            if (p.length() > MAX_TYPE_LENGTH || !PATTERN.matcher(p).matches()) {
                throw ApiException.badRequest("invalid_event_types", "invalid event type pattern: " + raw);
            }
            unique.add(p);
        }
        String joined = String.join(",", unique);
        if (joined.length() > 1024) {
            throw ApiException.badRequest("invalid_event_types", "too many event type patterns");
        }
        return joined;
    }

    public static List<String> split(String stored) {
        return Arrays.asList(stored.split(","));
    }

    public static boolean matches(String stored, String type) {
        for (String p : stored.split(",")) {
            if (p.equals("*") || p.equals(type)) {
                return true;
            }
            if (p.endsWith(".*") && type.startsWith(p.substring(0, p.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
