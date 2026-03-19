package com.aip.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DateTimeTool implements McpToolHandler {

    private final ObjectMapper objectMapper;

    public DateTimeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(name = "get_current_time", description = "获取当前时间，支持指定时区和输出格式")
    public String getCurrentTime(
            @ToolParam(required = false, description = "时区名称，例如 Asia/Shanghai、UTC、America/New_York") String timezone,
            @ToolParam(required = false, description = "时间格式，例如 yyyy-MM-dd HH:mm:ss") String format) {
        ZoneId zoneId = toZoneId(timezone);
        String pattern = hasText(format) ? format : "yyyy-MM-dd HH:mm:ss";
        return ZonedDateTime.now(zoneId).format(DateTimeFormatter.ofPattern(pattern));
    }

    @Tool(name = "timestamp_to_datetime", description = "把时间戳转换成可读时间")
    public String timestampToDateTime(
            @ToolParam(description = "时间戳，支持秒或毫秒") Long timestamp,
            @ToolParam(required = false, description = "时区名称") String timezone,
            @ToolParam(required = false, description = "时间格式，例如 yyyy-MM-dd HH:mm:ss") String format) {
        long epochMillis = timestamp < 10_000_000_000L ? timestamp * 1000 : timestamp;
        String pattern = hasText(format) ? format : "yyyy-MM-dd HH:mm:ss";
        ZonedDateTime dateTime = Instant.ofEpochMilli(epochMillis).atZone(toZoneId(timezone));
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    @Tool(name = "calculate_time_diff", description = "计算两个 ISO-8601 时间之间的差值")
    public String calculateTimeDiff(
            @ToolParam(description = "开始时间，例如 2026-03-19T10:00:00") String startTime,
            @ToolParam(description = "结束时间，例如 2026-03-20T12:00:00") String endTime) {
        Duration duration = Duration.between(LocalDateTime.parse(startTime), LocalDateTime.parse(endTime));
        long totalSeconds = Math.abs(duration.getSeconds());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", totalSeconds / 86400);
        result.put("hours", (totalSeconds % 86400) / 3600);
        result.put("minutes", (totalSeconds % 3600) / 60);
        result.put("seconds", totalSeconds % 60);
        result.put("totalSeconds", totalSeconds);
        result.put("isPositive", duration.getSeconds() >= 0);
        return toJson(result);
    }

    @Tool(name = "add_time", description = "对 ISO-8601 时间做加减运算")
    public String addTime(
            @ToolParam(description = "原始时间，例如 2026-03-19T10:00:00") String dateTime,
            @ToolParam(description = "增减数量，正数为加，负数为减") Integer amount,
            @ToolParam(description = "单位，可选 years、months、days、hours、minutes、seconds") String unit) {
        LocalDateTime current = LocalDateTime.parse(dateTime);
        LocalDateTime result = switch (unit.toLowerCase()) {
            case "years" -> current.plusYears(amount);
            case "months" -> current.plusMonths(amount);
            case "days" -> current.plusDays(amount);
            case "hours" -> current.plusHours(amount);
            case "minutes" -> current.plusMinutes(amount);
            case "seconds" -> current.plusSeconds(amount);
            default -> throw new IllegalArgumentException("不支持的时间单位: " + unit);
        };
        return result.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool(name = "is_leap_year", description = "判断指定年份是否为闰年")
    public String isLeapYear(@ToolParam(description = "年份，例如 2024") Integer year) {
        return String.valueOf(Year.of(year).isLeap());
    }

    @Tool(name = "getCurrentTime", description = "兼容 share 工具名，获取当前时间")
    public String getCurrentTimeCompat(
            @ToolParam(required = false, description = "时区名称，如 Asia/Shanghai") String timezone,
            @ToolParam(required = false, description = "输出格式") String format) {
        return getCurrentTime(timezone, format);
    }

    @Tool(name = "timestampToDateTime", description = "兼容 share 工具名，把时间戳转换成可读时间")
    public String timestampToDateTimeCompat(
            @ToolParam(description = "时间戳") Long timestamp,
            @ToolParam(required = false, description = "时区名称") String timezone,
            @ToolParam(required = false, description = "输出格式") String format) {
        return timestampToDateTime(timestamp, timezone, format);
    }

    @Tool(name = "calculateTimeDiff", description = "兼容 share 工具名，计算两个时间之间的差值")
    public String calculateTimeDiffCompat(
            @ToolParam(description = "开始时间") String startTime,
            @ToolParam(description = "结束时间") String endTime) {
        return calculateTimeDiff(startTime, endTime);
    }

    @Tool(name = "addTime", description = "兼容 share 工具名，对时间进行加减运算")
    public String addTimeCompat(
            @ToolParam(description = "原始时间") String dateTime,
            @ToolParam(description = "增减数量") Integer amount,
            @ToolParam(description = "单位") String unit) {
        return addTime(dateTime, amount, unit);
    }

    @Tool(name = "isLeapYear", description = "兼容 share 工具名，判断是否为闰年")
    public String isLeapYearCompat(@ToolParam(description = "年份") Integer year) {
        return isLeapYear(year);
    }

    private ZoneId toZoneId(String timezone) {
        return hasText(timezone) ? ZoneId.of(timezone) : ZoneId.systemDefault();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("时间结果序列化失败", e);
        }
    }
}
