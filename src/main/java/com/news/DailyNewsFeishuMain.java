package com.news;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class DailyNewsFeishuMain {

    private static final String API_KEY = System.getenv("ZHIPU_API_KEY");
    private static final String FEISHU_WEBHOOK = System.getenv("FEISHU_WEBHOOK_URL");

    private static final String MODEL_PRIMARY = "glm-4.7-flash";
    private static final String MODEL_BACKUP = "glm-4-flash-250414";
    private static final int MAX_RETRY = 3;
    private static final long SLEEP_BETWEEN_CATEGORY = 2000;

    private static final Pattern DOCTYPE_PATTERN = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE);

    static class FeedConfig {
        public String category;
        public String feedUrl;
        public FeedConfig(String category, String feedUrl) {
            this.category = category;
            this.feedUrl = feedUrl;
        }
    }

    private static final List<FeedConfig> FEED_CONFIG_LIST = List.of(
            new FeedConfig("world", "https://www.chinanews.com.cn/rss/world.xml"),
            new FeedConfig("tech", "https://www.ithome.com/rss/"),
            new FeedConfig("tech", "https://sspai.com/feed"),
            new FeedConfig("industry", "https://www.tmtpost.com/feed"),
            new FeedConfig("life", "https://news.cctv.com/rss/life.xml")
    );

    private static final Map<String, String> CATEGORY_DISPLAY = new LinkedHashMap<>();
    static {
        CATEGORY_DISPLAY.put("world", "🗺️ 国际时事");
        CATEGORY_DISPLAY.put("tech", "🖥️ 科技数码");
        CATEGORY_DISPLAY.put("industry", "📈 产业商业");
        CATEGORY_DISPLAY.put("life", "🍃 生活民生");
    }

    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static class RssNews {
        public String title;
        public String link;
        public String source;
        public Date pubDate;
    }

    public static void main(String[] args) {
        ZoneId shanghaiZone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(shanghaiZone);
        String todayStr = today.format(DATE_FMT);
        System.out.println("====开始执行每日新闻任务 日期:" + todayStr + "====");

        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("错误：环境变量 ZHIPU_API_KEY 未设置");
            return;
        }
        if (FEISHU_WEBHOOK == null || FEISHU_WEBHOOK.isBlank()) {
            System.err.println("错误：环境变量 FEISHU_WEBHOOK_URL 未设置");
            return;
        }

        try {
            Map<String, List<RssNews>> categoryNewsMap = fetchGroupedTodayNews(today, shanghaiZone);

            StringBuilder fullMarkdown = new StringBuilder();
            fullMarkdown.append("# 📰每日新闻简报｜").append(todayStr).append("\n");
            fullMarkdown.append("> 自动聚合RSS源今日新闻，AI摘要整理\n\n");

            for (Map.Entry<String, String> catEntry : CATEGORY_DISPLAY.entrySet()) {
                String catKey = catEntry.getKey();
                String catShowName = catEntry.getValue();
                List<RssNews> newsList = categoryNewsMap.getOrDefault(catKey, new ArrayList<>());

                fullMarkdown.append("## ").append(catShowName).append("\n");
                if (newsList.isEmpty()) {
                    fullMarkdown.append("*该分类今日暂无新闻*\n\n\n");
                    continue;
                }

                List<RssNews> sliceList = newsList.size() > 8 ? newsList.subList(0, 8) : newsList;
                StringBuilder materialSb = new StringBuilder();
                for (int i = 0; i < sliceList.size(); i++) {
                    RssNews item = sliceList.get(i);
                    materialSb.append(i + 1).append(". 标题：").append(item.title)
                            .append(" | 来源：").append(item.source)
                            .append(" | url：").append(item.link).append("\n");
                }

                String prompt = """
                        你是新闻简报编辑，请处理下面新闻素材。
                        硬性约束：
                        1. 只允许使用提供的素材，严禁编造新闻、严禁编造url链接。
                        2. 筛选最多6条最重要新闻，数字序号1.2.3.4.5.6逐条输出。
                        3. 摘要简洁，讲清楚事件主体、时间、结果。
                        4. 输出格式：
                        1. **新闻标题**：简短摘要
                        > 信息来源：[来源名](链接)

                        5. 不要多余开场白，直接输出条目。
                        【新闻素材】
                        %s
                        """.formatted(materialSb);

                String catResult;
                try {
                    catResult = callLlmWithFallback(prompt);
                } catch (Exception e) {
                    System.err.println("分类[" + catKey + "]AI摘要失败:" + e.getMessage());
                    StringBuilder fallbackSb = new StringBuilder();
                    for (int i = 0; i < Math.min(6, sliceList.size()); i++) {
                        RssNews item = sliceList.get(i);
                        fallbackSb.append(String.format("%d. **%s**\n> 信息来源：[%s](%s)\n",
                                i + 1, item.title, item.source, item.link));
                    }
                    catResult = fallbackSb.toString();
                }
                fullMarkdown.append(catResult).append("\n\n\n");
                Thread.sleep(SLEEP_BETWEEN_CATEGORY);
            }

            System.out.println("\n===最终简报===");
            System.out.println(fullMarkdown);
            sendFeishuCard("📰每日新闻简报｜" + todayStr, fullMarkdown.toString());
            System.out.println("====任务执行完成====");

        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendFeishuCard("❌每日新闻任务异常", "程序异常：" + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public static Map<String, List<RssNews>> fetchGroupedTodayNews(LocalDate today, ZoneId zoneId) {
        Map<String, List<RssNews>> map = new HashMap<>();
        for (String cat : CATEGORY_DISPLAY.keySet()) {
            map.put(cat, new ArrayList<>());
        }

        for (FeedConfig cfg : FEED_CONFIG_LIST) {
            String rssUrl = cfg.feedUrl;
            String category = cfg.category;
            try {
                URL url = new URL(rssUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                String rawXml;
                try (InputStream is = conn.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    rawXml = DOCTYPE_PATTERN.matcher(sb.toString()).replaceAll("");
                }
                conn.disconnect();

                SyndFeedInput input = new SyndFeedInput();
                InputSource inputSource = new InputSource(new java.io.StringReader(rawXml));
                SyndFeed feed = input.build(inputSource);
                String sourceName = feed.getTitle() != null ? feed.getTitle() : rssUrl;

                for (SyndEntry entry : feed.getEntries()) {
                    Date pubDate = entry.getPublishedDate();
                    if (pubDate == null) continue;
                    LocalDate itemDate = pubDate.toInstant().atZone(zoneId).toLocalDate();
                    if (!today.isEqual(itemDate)) continue;

                    RssNews news = new RssNews();
                    news.title = entry.getTitle();
                    news.link = entry.getLink();
                    news.source = sourceName;
                    news.pubDate = pubDate;
                    map.get(category).add(news);
                }
            } catch (Exception ex) {
                System.err.println("RSS读取失败：" + rssUrl + " err:" + ex.getMessage());
            }
        }
        return map;
    }

    private static String callLlmWithFallback(String prompt) throws Exception {
        String respBody;
        try {
            respBody = callZhipuWithRetry(prompt, MODEL_PRIMARY);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("1302") || e.getMessage().contains("1305")) {
                System.out.println("主模型限流，切换备份模型");
                respBody = callZhipuWithRetry(prompt, MODEL_BACKUP);
            } else {
                throw e;
            }
        }
        JSONObject json = JSON.parseObject(respBody);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("choices为空");
        JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
        return msg.getString("content");
    }

    public static String callZhipuWithRetry(String prompt, String modelName) throws Exception {
        int retryCount = 0;
        while (retryCount < MAX_RETRY) {
            try {
                return callZhipu(prompt, modelName);
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                if ((msg.contains("1302") || msg.contains("1305")) && retryCount < MAX_RETRY - 1) {
                    retryCount++;
                    long sleep = (long) (Math.pow(2, retryCount) * 1000);
                    System.out.printf("限流重试 %d，sleep %dms%n", retryCount, sleep);
                    Thread.sleep(sleep);
                } else {
                    throw ex;
                }
            }
        }
        throw new RuntimeException("超过最大重试次数");
    }

    public static String callZhipu(String prompt, String modelName) throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(120000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);

        JSONObject req = new JSONObject();
        req.put("model", modelName);
        req.put("temperature", 0.3);
        req.put("max_tokens", 8192);
        JSONArray msgs = new JSONArray();
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", prompt);
        msgs.add(m);
        req.put("messages", msgs);

        byte[] body = req.toJSONString().getBytes(StandardCharsets.UTF_8);
        conn.getOutputStream().write(body);

        int code = conn.getResponseCode();
        byte[] respBytes;
        if (code == 200) {
            respBytes = conn.getInputStream().readAllBytes();
        } else {
            respBytes = conn.getErrorStream() != null ? conn.getErrorStream().readAllBytes() : new byte[0];
        }
        conn.disconnect();
        String resp = new String(respBytes, StandardCharsets.UTF_8);
        if (code != 200) {
            throw new RuntimeException("智谱API code:" + code + " body:" + resp);
        }
        return resp;
    }

    public static void sendFeishuCard(String title, String markdownContent) throws Exception {
        JSONObject card = new JSONObject();
        card.put("wide_screen_mode", true);
        JSONObject header = new JSONObject();
        JSONObject titleObj = new JSONObject();
        titleObj.put("tag", "plain_text");
        titleObj.put("content", title);
        header.put("title", titleObj);
        card.put("header", header);
        JSONArray elements = new JSONArray();
        JSONObject mdItem = new JSONObject();
        mdItem.put("tag", "markdown");
        mdItem.put("content", markdownContent);
        elements.add(mdItem);
        card.put("elements", elements);

        JSONObject payload = new JSONObject();
        payload.put("msg_type", "interactive");
        payload.put("card", card);

        URL url = new URL(FEISHU_WEBHOOK);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
        byte[] payloadBytes = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
        conn.getOutputStream().write(payloadBytes);

        int respCode = conn.getResponseCode();
        byte[] respBytes = respCode >=200 && respCode <300 ? conn.getInputStream().readAllBytes() :
                (conn.getErrorStream()!=null ? conn.getErrorStream().readAllBytes() : new byte[0]);
        conn.disconnect();
        String respText = new String(respBytes, StandardCharsets.UTF_8);
        JSONObject respJson = JSON.parseObject(respText);
        Integer bizCode = respJson.getInteger("code");
        if (bizCode != null && bizCode != 0) {
            throw new RuntimeException("飞书接口异常 code=" + bizCode + " msg=" + respJson.getString("msg"));
        }
    }
}
