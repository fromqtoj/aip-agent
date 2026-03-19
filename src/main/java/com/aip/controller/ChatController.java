package com.aip.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 提供基础聊天相关接口。
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    private static final String DEFAULT_PROMPT = "你是一个博学的智能聊天助手，请根据用户提问回答。";


    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder  chatClientBuilder){
        this.chatClient  = chatClientBuilder
                .defaultSystem(DEFAULT_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7).build()
                ).build();
    }



    @GetMapping("/simple/chat")
    public String simpleChat(String question){
        return chatClient.prompt(question).call().content();
    }



    @GetMapping("/stream/chat")
    public Flux<String> streamChat(@RequestParam("question") String question, HttpServletResponse response){
        response.setCharacterEncoding("utf-8");
        return chatClient.prompt(question).stream().content();
    }



}
