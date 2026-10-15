package com.owrtp.config;

import com.owrtp.OwRtpPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Đọc thông báo từ config.yml, hỗ trợ MiniMessage và màu legacy (&a, &#rrggbb). */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern LEGACY = Pattern.compile("(?i)&(#[0-9a-f]{6}|[0-9a-fk-or])");
    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private final OwRtpPlugin plugin;

    public Messages(OwRtpPlugin plugin) {
        this.plugin = plugin;
    }

    /** Gửi thông báo vào chat. kv = cặp (tên placeholder, giá trị). */
    public void send(CommandSender target, String key, String... kv) {
        Component c = component(key, kv);
        if (c != null) {
            target.sendMessage(c);
        }
    }

    /** Gửi thông báo lên actionbar. */
    public void actionBar(Player target, String key, String... kv) {
        Component c = component(key, kv);
        if (c != null) {
            target.sendActionBar(c);
        }
    }

    public Component component(String key, String... kv) {
        String raw = raw(key);
        if (raw.isEmpty()) {
            return null; // để trống trong config = tắt thông báo
        }
        raw = raw.replace("{prefix}", rawString("messages.prefix"));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return MM.deserialize(legacyToMini(raw));
    }

    private String raw(String key) {
        return rawString("messages." + key);
    }

    private String rawString(String path) {
        FileConfiguration c = plugin.getConfig();
        if (c.isList(path)) {
            return String.join("<newline>", c.getStringList(path));
        }
        String s = c.getString(path);
        return s == null ? "" : s;
    }

    private static String legacyToMini(String input) {
        if (input.indexOf('&') < 0) {
            return input;
        }
        Matcher m = LEGACY.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1).toLowerCase(Locale.ROOT);
            String tag = code.startsWith("#") ? "<" + code + ">" : "<" + LEGACY_TAGS.get(code.charAt(0)) + ">";
            m.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 3725 -> "1h 2m 5s" */
    public static String formatTime(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0s";
        }
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
