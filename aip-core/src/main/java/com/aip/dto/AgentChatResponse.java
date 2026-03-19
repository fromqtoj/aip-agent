package com.aip.dto;

import java.util.List;

public class AgentChatResponse {

    private String conversationId;

    private String answer;

    private List<String> fileNames;

    public AgentChatResponse() {
    }

    public AgentChatResponse(String conversationId, String answer, List<String> fileNames) {
        this.conversationId = conversationId;
        this.answer = answer;
        this.fileNames = fileNames;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getFileNames() {
        return fileNames;
    }

    public void setFileNames(List<String> fileNames) {
        this.fileNames = fileNames;
    }
}
