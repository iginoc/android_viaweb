package com.igino.android_viaweb

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class FavoritesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<String>>() {}.type

    fun saveFavorites(favorites: List<String>) {
        val jsonFavorites = gson.toJson(favorites, type)
        prefs.edit().putString("favorite_folders", jsonFavorites).apply()
    }

    fun loadFavorites(): List<String> {
        val jsonFavorites = prefs.getString("favorite_folders", null)
        return if (jsonFavorites != null) {
            try {
                gson.fromJson<List<String>>(jsonFavorites, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}
