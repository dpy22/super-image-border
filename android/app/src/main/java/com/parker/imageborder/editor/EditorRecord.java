package com.parker.imageborder.editor;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="editor_records")
public class EditorRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String kind = "draft";
    @NonNull public String title = "";
    @NonNull public String payload = "{}";
    public long updated = 0;
}
