package com.parker.imageborder.editor;
import androidx.room.Database;
import androidx.room.RoomDatabase;
@Database(entities={EditorRecord.class},version=1,exportSchema=false)
public abstract class EditorDatabase extends RoomDatabase { public abstract EditorDao records(); }
