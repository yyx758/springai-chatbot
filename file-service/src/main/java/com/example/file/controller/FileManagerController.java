package com.example.file.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FileManagerController {

    @GetMapping("/admin/files")
    public String fileManager() {
        return "file-manager";
    }
}
