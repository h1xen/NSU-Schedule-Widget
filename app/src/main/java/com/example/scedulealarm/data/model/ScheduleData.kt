package com.example.scedulealarm.data.model

import com.google.gson.annotations.SerializedName

data class ScheduleData(
    @SerializedName("groupName")
    val groupName: String,

    @SerializedName("groupUrl")
    val groupUrl: String,

    @SerializedName("fetchedAtMillis")
    val fetchedAtMillis: Long,

    @SerializedName("baseParity")
    val baseParity: WeekParity,

    @SerializedName("lessons")
    val lessons: List<Lesson>
)
