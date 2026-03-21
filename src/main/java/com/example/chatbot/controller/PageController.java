package com.example.chatbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 * 用于展示智能客服聊天页面
 * 
 * @author yyvb
 */
@Controller
public class PageController {

    /**
     * 主页面 - 智能客服聊天界面
     */
    @GetMapping("/")
    public String index() {
        return "chat";
    }

    /**
     * 聊天页面 - 与根路径相同
     */
    @GetMapping("/chat")
    public String chat() {
        return "chat";
    }
} 