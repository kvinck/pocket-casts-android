package au.com.shiftyjelly.pocketcasts.models.converter

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

@ProvidedTypeConverter
class StringListConverter(
    moshi: Moshi,
) {
    private val adapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java),
    )

    @TypeConverter
    fun toStringList(value: String?): List<String> = value?.let { adapter.fromJson(it) }.orEmpty()

    @TypeConverter
    fun toJsonString(values: List<String>): String = adapter.toJson(values)
}
