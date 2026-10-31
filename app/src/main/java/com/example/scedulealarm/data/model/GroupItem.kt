package com.example.scedulealarm.data.model

import com.google.gson.annotations.SerializedName

data class GroupItem(
    @SerializedName("name")
    val name: String,
    @SerializedName("urlPath")
    val urlPath: String
) {
    override fun toString(): String = name

    companion object {
        val DEFAULT = GroupItem(
            name = "26946",
            urlPath = "group/26946"
        )
    }
}
