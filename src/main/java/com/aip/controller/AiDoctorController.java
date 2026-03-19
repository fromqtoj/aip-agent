package com.aip.controller;

import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 提供 AI 医疗咨询及历史消息查询接口。
 */
@RestController
@RequestMapping("/ai/doctor")
public class AiDoctorController {

    // 系统提示词模板
    private static final String SYSTEM_PROMPT = """
        你是一位基于医学知识的AI健康咨询助手，你的职责是：
        1. 礼貌询问用户的健康问题（症状、持续时间、年龄、基础疾病等关键信息）
        2. 基于用户描述提供可能的健康建议（非诊断结果）
        3. 明确提示"本建议仅供参考，不替代专业医师诊断"
        4. 对紧急症状（如胸痛、严重出血等）优先建议立即就医
        
        回复要求：
        - 语言通俗易懂，避免专业术语堆砌
        - 结构清晰：先回应问题，再给出建议，最后提示就医
        - 不承诺治愈效果，不提供具体用药剂量建议
        - 对超出常识的问题，引导用户咨询专业医生
        """;

    private final ChatClient chatClient;

    private final int MAX_MESSAGES = 100;

    private final MessageWindowChatMemory messageWindowChatMemory;

    public AiDoctorController(ChatClient.Builder builder, RedisChatMemoryRepository redisChatMemoryRepository){
        //构建消息聊天内存管理器
        this.messageWindowChatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                .maxMessages(MAX_MESSAGES)
                .build();

        //构建聊天客户端
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(messageWindowChatMemory).build()).build();

    }


    /**
     * 处理医疗咨询对话请求。
     *
     * @param query 用户输入的问题
     * @param conversionId 会话 ID
     * @param response HTTP 响应对象
     * @return 流式响应内容
     */
    @GetMapping("/call")
    public Flux<String> chat(@RequestParam("query")String query,
                             @RequestParam("conversion_id")String conversionId,
                             HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        Flux<String> content = chatClient.prompt(query)
                .advisors(
                        a -> a.param(CONVERSATION_ID, conversionId)
                )
                .stream().content();
        return content;
    }


    /**
     * 查询指定会话的历史消息。
     */
    @GetMapping("/messages")
    public List<Message> list(@RequestParam("conversion_id")String conversionId){
        List<Message> messages = messageWindowChatMemory.get(conversionId);
        return messages;
    }





}
