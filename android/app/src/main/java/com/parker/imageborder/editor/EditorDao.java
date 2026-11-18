package com.parker.imageborder.editor;
import androidx.room.*;
import java.util.List;
@Dao public interface EditorDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) void put(EditorRecord record);
    @Query("SELECT * FROM editor_records WHERE kind = :kind ORDER BY updated DESC") List<EditorRecord> list(String kind);
    @Query("SELECT * FROM editor_records WHERE id = :id LIMIT 1") EditorRecord get(String id);
    @Query("DELETE FROM editor_records WHERE id = :id") void delete(String id);
}
