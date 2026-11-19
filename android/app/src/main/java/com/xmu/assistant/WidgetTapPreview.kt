package com.xmu.assistant

/**
 * 这里曾放「两段式点击」状态（第一次点展开预览、第二次点才进 App），
 * 后改为点一下就弹课程详情二级界面，状态对象整体删除。
 * 本文件只保留课表视图名常量（多处组件与深链共用）。
 */

/** 课表页两种视图的名字：与 SchedulePage 内部的 ScheduleViewMode 枚举逐字一致。 */
internal object ScheduleViewModeNames {
    const val WEEK = "WEEK"
    const val AGENDA = "AGENDA"
}
