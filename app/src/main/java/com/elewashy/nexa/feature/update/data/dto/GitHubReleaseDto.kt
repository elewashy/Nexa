package com.elewashy.nexa.feature.update.data.dto

import com.google.gson.annotations.SerializedName

data class GitHubReleaseDto(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("prerelease") val prerelease: Boolean,
    @SerializedName("draft") val draft: Boolean,
    @SerializedName("assets") val assets: List<GitHubAssetDto>
)

data class GitHubAssetDto(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String,
    @SerializedName("size") val size: Long
)
