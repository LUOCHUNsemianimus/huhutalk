package com.huhutalk;

import com.huhutalk.core.archive.ArchiveAssetSessionBehaviorCheck;
import com.huhutalk.core.archive.ArchiveHistoryBehaviorCheck;
import com.huhutalk.core.archive.ArchiveMediaBehaviorCheck;
import com.huhutalk.core.archive.ArchiveStoreBehaviorCheck;
import com.huhutalk.core.archive.ArchiveTransferBehaviorCheck;
import com.huhutalk.core.rescue.RescueNarrationBehaviorCheck;
import org.junit.Test;

/**
 * 真实 JUnit 入口：逐项调用既有行为检查的 main，不复制断言、不改原检查。
 *
 * <p>既有 main 在失败时会抛出 AssertionError 或以 System.exit(1) 结束；通过时不退出，
 * 因此这里直接复用即可让 Gradle test 在无失败时正常报告 6 项测试。
 */
public final class CoreBehaviorTest {

    @Test
    public void archiveStoreBehavior() throws Exception {
        ArchiveStoreBehaviorCheck.main(new String[0]);
    }

    @Test
    public void archiveHistoryBehavior() throws Exception {
        ArchiveHistoryBehaviorCheck.main(new String[0]);
    }

    @Test
    public void archiveMediaBehavior() throws Exception {
        ArchiveMediaBehaviorCheck.main(new String[0]);
    }

    @Test
    public void archiveAssetSessionBehavior() throws Exception {
        ArchiveAssetSessionBehaviorCheck.main(new String[0]);
    }

    @Test
    public void archiveTransferBehavior() throws Exception {
        ArchiveTransferBehaviorCheck.main(new String[0]);
    }

    @Test
    public void rescueNarrationBehavior() throws Exception {
        RescueNarrationBehaviorCheck.main(new String[0]);
    }
}
