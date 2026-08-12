package com.huhutalk.phone.pages;

import java.util.UUID;

/**
 * 临时女仆数据载体，可替换为你的实际女仆对象。
 */
public record MaidEntry(UUID uuid, String name, boolean online) {}