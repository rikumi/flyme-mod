# 弹出与锁屏通知 8dp 容器隔离

目标为两台设备相同的 SystemUI 16260625 / 16.26.06.25_base。原生 NotificationStackScrollLayout.onMeasure 向全部子行传递扣除 mSidePaddings 的规格；onLayout 逐行按各自 measuredWidth 居中。仅直接父级为该堆栈的通知 row 在 KEYGUARD=1，或 SHADE=0 且未展开并正在 pinned/animating-away 呈现时改为父宽减两侧各 8dp。SHADE_LOCKED=2 始终保持原生规格，不按 NotificationEntry、通知内容或遗留 row keyguard 标志判定。组内行不独立改宽，由外层组卡片原生布局处理。

合并控制中心通过实际堆栈 mShadeInteractor.isClassicsMode().getValue()、mIsExpanded、onKeyguard() 和 isFilterStackLayout() 判定，明确排除其通知呈现容器；不会仅按全局合并模式排除普通弹出或锁屏通知。CombinedNotificationWidthHooks 独立保留原有两侧各 16dp 的增宽规则，不再调用浮动通知的 8dp 辅助方法。未启用合并中心增宽时仍传递原生/缩放布局的测量规格，退出时原生 updateSidePadding 重新写入本容器边距，不保存或复制其它呈现容器的宽度。

只替换本次 onMeasure 参数，不写 margin、padding、布局参数或全局 mSidePaddings；父容器状态和 row 的 pinned/heads-up/隐藏公开内容发生有效变化时请求重测；对子 row forceLayout，使原始规格相同的 View.measure 缓存不能保留旧宽度。重复状态回调不请求重测，避免原生每次 row state 更新触发布局循环。关闭开关通过设置观察器请求各已知容器重测。对照 setExpandedHeight(float) 与 Controller$6.onStateChanged(int) 调用桥并 deoptimize，避免内联状态 setter 跳过测量更新。

横屏真实模板为 LandscapeHeadsUpNotificationView：386dp 卡片位于无横向 padding 的 wrap_content RelativeLayout，外部 WindowManager 窗口 gravity=TOP|CENTER_HORIZONTAL。仅该独立模板的 onMeasure 改为屏幕宽度减 16dp，父级及窗口按原生居中；普通 LinearLayout 不改变。

`test_width_isolation.py` 执行实际生产类，349 项检查涵盖共享同一 entry 的两个呈现容器、合并/普通中心实际宽度辅助方法与 row hook 联合执行、合并模式与全部呈现状态组合、16dp/8dp 独立计算、同 row 进入/退出合并中心、锁屏/锁定 shade、组内行、状态转换、公开内容重测、横屏模板、复用、不同密度、开关回退和非累加。`test_public_padding.py` 的 136 项历史内边距/高度检查仍通过。未创建通知、切换锁屏或重启 SystemUI，实机边缘距离、动画、公开/私密转换仍未验收。
