package com.example.file.storage;

import java.io.InputStream;

public interface FileStorage {

    /**
     * 存储文件
     * @param data 文件输入流
     * @param fileKey 文件唯一键
     * @param contentType MIME 类型
     * @return 存储路径
     */
    String store(InputStream data, String fileKey, String contentType);

    /**
     * 加载文件
     * @param fileKey 文件唯一键
     * @return 文件输入流
     */
    InputStream load(String fileKey);

    /**
     * 删除文件
     * @param fileKey 文件唯一键
     * @return 是否删除成功
     */
    boolean delete(String fileKey);

    /**
     * 获取文件访问 URL
     * @param fileKey 文件唯一键
     * @return 访问 URL
     */
    String getUrl(String fileKey);

    /**
     * 检查文件是否存在
     * @param fileKey 文件唯一键
     * @return 是否存在
     */
    boolean exists(String fileKey);
}
