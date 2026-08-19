package com.huhutalk;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager;
import com.huhutalk.core.move.MoveToTool;
import com.huhutalk.core.move.TaskMoveTo;
import com.huhutalk.init.HuhuTalkItems;

/**
 * 车万女仆(TLM)扩展入口。
 * <p>
 * 通过 {@link LittleMaidExtension} 注解被 TLM 扫描发现（见 AnnotatedInstanceUtil），
 * 需要提供无参构造。这里把本模组的手机物品绑定为女仆配饰，
 * 使它们能被放入女仆配饰栏，并被 {@code BaubleItemHandler.containsItem} 检测到。
 */
@LittleMaidExtension
public class HuhuTalkTLMExtension implements ILittleMaid {

    @Override
    public void bindMaidBauble(BaubleManager manager) {
        // 手机物品本体即 IMaidBauble 实现，直接绑定
        manager.bind(HuhuTalkItems.PHONE_WATCH.get(), HuhuTalkItems.PHONE_WATCH.get());
        manager.bind(HuhuTalkItems.PHONE_SMART.get(), HuhuTalkItems.PHONE_SMART.get());
    }

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(TaskMoveTo.INSTANCE);
    }

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new MoveToTool());
    }
}
