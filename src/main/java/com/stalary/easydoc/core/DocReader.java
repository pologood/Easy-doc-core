/**
 * @(#)DocReader.java, 2018-09-27.
 * <p>
 * Copyright 2018 Stalary.
 */
package com.stalary.easydoc.core;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.stalary.easydoc.config.EasyDocProperties;
import com.stalary.easydoc.config.SystemConfiguration;
import com.stalary.easydoc.data.*;
import com.stalary.easydoc.web.RegularExpressionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * DocReader
 *
 * @author lirongqian
 * @since 2018/09/27
 */
@Slf4j
@Component
public class DocReader {

    private View viewCache;

    public View view;

    @Autowired
    ReflectUtils reflectUtils;

    @Autowired
    private DocHandler docHandler;

    @Autowired
    private SystemConfiguration systemConfiguration;

    private EasyDocProperties properties;

    public DocReader(EasyDocProperties properties) {
        this.properties = properties;
    }

    /**
     * getFile 获取文件
     * @param file 传入文件
     * @param fileList 生成的文件列表
     **/
    private void getFile(File file, List<File> fileList) {
        if (file.exists()) {
            if (file.isFile()) {
                fileList.add(file);
            } else if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) {
                    for (File single : files) {
                        getFile(single, fileList);
                    }
                }
            }
        }
    }

    /**
     * readFile 读取单个文件
     * @param file 文件
     * @return 内容
     **/
    private String readFile(File file) {
        // 此处设置编码，解决乱码问题
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(new FileInputStream(file),
                             Charset.forName("UTF-8")))) {
            StringBuilder sb = new StringBuilder();
            String s = reader.readLine();
            while (s != null) {
                sb.append(s);
                s = reader.readLine();
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("readFile error!", e);
        }
        return "";
    }

    /**
     * commonReader 公共读入方法
     **/
    private void commonReader() {
        docHandler.addSuperModel(view);
        docHandler.addURL(view);
        // 缓存
        viewCache = view;
    }

    /**
     * multiReader 多文件读入方法
     * @return 前端渲染对象
     **/
    public View multiReader() {
        if (viewCache != null) {
            return viewCache;
        }
        view = new View(properties);
        String fileName = Constant.CUR_PATH + Constant.JAVA + properties.getPath().replaceAll("\\.", "/");
        File file = new File(fileName);
        List<File> fileList = new ArrayList<>();
        getFile(file, fileList);
        pathMapper(fileList);
        for (File aFileList : fileList) {
            singleReader(aFileList);
        }
        commonReader();
        return view;
    }

    /**
     * multiReader 多匹配后字符串渲染方法
     * @param str 匹配后的字符串
     * @return 前端渲染对象
     **/
    public View multiReader(String str) {
        if (viewCache != null) {
            return viewCache;
        }
        view = new View(properties);
        String[] pathSplit = str.split(Constant.PATH_SPLIT);
        Constant.PATH_MAP.putAll(JSONObject.parseObject(pathSplit[0], new TypeReference<Map<String, String>>() {
        }));
        String[] fileSplit = pathSplit[1].split(Constant.FILE_SPLIT);
        for (String temp : fileSplit) {
            try {
                singleReader(temp);
            } catch (Exception e) {
                log.warn("singleReader error file: " + temp);
            }
        }
        commonReader();
        return view;
    }

    /**
     * singleReader 单匹配后字符串渲染方法
     * @param str 传入匹配后的字符串
     **/
    private void singleReader(String str) {
        String[] split = str.split(Constant.MATCH_SPLIT);
        String name = split[0];
        Controller controller = new Controller();
        Model model = new Model();
        if (split.length > 1) {
            for (int i = 1; i < split.length; i++) {
                docHandler.handle(controller, model, split[i], name, view);
            }
        }
    }

    /**
     * singleReader 单文件读入方法
     * @param file 文件
     **/
    private void singleReader(File file) {
        try {
            // 获取文件名称
            String fileName = file.getName();
            String name = fileName.substring(0, fileName.indexOf("."));
            Controller controller = new Controller();
            Model model = new Model();
            String str = readFile(file);
            // 匹配出注释代码块
            String regex = "\\/\\*(\\s|.)*?\\*\\/";
            Matcher matcher = RegularExpressionUtils.createMatcherWithTimeout(str, regex, 200);
            while (matcher.find()) {
                String temp = "";
                try {
                    // 1. 去除所有单行注释
                    // 2. 匹配块级注释
                    // 3. 合并多个空格
                    temp = matcher
                            .group()
                            .replaceAll("\\/\\*\\*", "")
                            .replaceAll("\\*\\/", "")
                            .replaceAll("\\*", "")
                            .replaceAll(" +", " ");
                    docHandler.handle(controller, model, temp, name, view);
                } catch (Exception e) {
                    log.warn("matcher error, please check it. skip..." + "\n" + name + " : " + temp);
                    log.warn("error: " + e);
                }
            }
        } catch (Exception e) {
            log.warn("singleReader error!", e);
        }
    }

    /**
     * pathMapper 路径映射生成方法
     * @param fileList 文件列表
     **/
    private void pathMapper(List<File> fileList) {
        fileList.forEach(file -> {
            NamePack namePack = path2Pack(file.getPath());
            Constant.PATH_MAP.put(namePack.getName(), namePack.getPackPath());
        });
    }

    /**
     * path2Pack 将文件路径转化为类名:包路径的映射
     * @param path 路径
     * @return 类名:包路径的映射
     **/
    private NamePack path2Pack(String path) {
        String temp;
        if (systemConfiguration.isWindows()) {
            temp = path.replaceAll("\\\\", ".");
        } else {
            temp = path.replaceAll("/", ".");
        }
        String packPath = temp.substring(temp.indexOf(properties.getPath()));
        packPath = packPath.substring(0, packPath.lastIndexOf("."));
        return new NamePack(packPath.substring(packPath.lastIndexOf(".") + 1), packPath);
    }

}