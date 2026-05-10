package com.example.chatbot.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final RedisTemplate<String, Object> redisTemplate;
    private final org.springframework.core.env.Environment env;

    private static final String CODE_KEY_PREFIX = "email_code:";
    private static final long CODE_TTL_MINUTES = 5;
    private static final long SEND_INTERVAL_SECONDS = 60;

    public void sendVerificationCode(String toEmail) {
        String intervalKey = "email_send_interval:" + toEmail;
        Boolean canSend = redisTemplate.opsForValue().setIfAbsent(intervalKey, "1",
                SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(canSend)) {
            throw new IllegalStateException("发送过于频繁，请60秒后再试");
        }

        String code = String.format("%06d", new Random().nextInt(999999));
        String key = CODE_KEY_PREFIX + toEmail;
        redisTemplate.opsForValue().set(key, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            // 读 spring.mail.username（YAML 中的 ${SMTP_USERNAME:}）；
            // 如果 YAML 也没读到（systemd env 传不进 Spring），fallback 到 spring.mail.properties.from
            String from = env.getProperty("spring.mail.username", "");
            if (from.isBlank()) from = env.getProperty("SMTP_USERNAME", "");
            if (from.isBlank()) from = env.getProperty("spring.mail.properties.from", "");
            if (from.isBlank()) throw new IllegalStateException("发件人邮箱未配置，请检查 SMTP_USERNAME");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("AI Studio - 邮箱验证码");
            helper.setText(buildEmailContent(code), true);
            mailSender.send(message);
            log.info("验证码已发送至 {}，有效期 {} 分钟", toEmail, CODE_TTL_MINUTES);
        } catch (MessagingException e) {
            redisTemplate.delete(key);
            log.error("邮件发送失败: {}", e.getMessage(), e);
            throw new IllegalStateException("邮件发送失败，请稍后重试");
        }
    }

    public boolean verifyCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        Object stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            return false;
        }
        if (!code.equals(String.valueOf(stored))) {
            return false;
        }
        redisTemplate.delete(key);
        return true;
    }

    private String buildEmailContent(String code) {
        return """
                <div style="max-width:480px;margin:0 auto;padding:32px 24px;
                            font-family:'Inter','Noto Sans SC',sans-serif;
                            background:#f8f9fa;border-radius:16px;text-align:center;">
                    <h2 style="color:#111827;margin-bottom:8px;">AI Studio</h2>
                    <p style="color:#64748b;font-size:14px;">您的邮箱验证码</p>
                    <div style="font-size:36px;font-weight:700;letter-spacing:6px;
                                color:#111827;background:#fff;display:inline-block;
                                padding:16px 32px;border-radius:12px;margin:16px 0;">
                        %s
                    </div>
                    <p style="color:#94a3b8;font-size:12px;">验证码 %d 分钟内有效，请勿泄露</p>
                </div>
                """.formatted(code, CODE_TTL_MINUTES);
    }
}
