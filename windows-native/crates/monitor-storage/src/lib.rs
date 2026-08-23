#![forbid(unsafe_code)]

use anyhow::{Context, Result, bail};
use atomicwrites::{AllowOverwrite, AtomicFile};
use chrono::{DateTime, Utc};
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use std::{
    env, fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RecordingMode {
    Off,
    Continuous,
    Schedule,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct RecordingSchedule {
    /// 每日开始时刻，格式 HH:MM
    pub start: String,
    /// 每日结束时刻，格式 HH:MM；小于等于 start 时跨午夜
    pub end: String,
    /// 启用的星期（ISO：1=周一 … 7=周日），空表示每天
    pub days: Vec<u8>,
}

impl Default for RecordingSchedule {
    fn default() -> Self {
        Self {
            start: "00:00".into(),
            end: "23:59".into(),
            days: Vec::new(),
        }
    }
}

impl RecordingSchedule {
    pub fn parse_minutes(value: &str) -> Option<u32> {
        let (hour, minute) = value.split_once(':')?;
        let hour: u32 = hour.parse().ok()?;
        let minute: u32 = minute.parse().ok()?;
        (hour < 24 && minute < 60).then_some(hour * 60 + minute)
    }

    pub fn is_valid(&self) -> bool {
        Self::parse_minutes(&self.start).is_some()
            && Self::parse_minutes(&self.end).is_some()
            && self.days.iter().all(|day| (1..=7).contains(day))
    }

    /// 判断本地时间（ISO 星期 + 当日分钟数）是否落在录制时段内，支持跨午夜时段。
    pub fn contains(&self, weekday_iso: u8, minute_of_day: u32) -> bool {
        let (Some(start), Some(end)) = (
            Self::parse_minutes(&self.start),
            Self::parse_minutes(&self.end),
        ) else {
            return false;
        };
        if start == end {
            return false;
        }
        let day_enabled = |day: u8| self.days.is_empty() || self.days.contains(&day);
        if start < end {
            day_enabled(weekday_iso) && minute_of_day >= start && minute_of_day < end
        } else {
            let previous_day = if weekday_iso <= 1 { 7 } else { weekday_iso - 1 };
            (day_enabled(weekday_iso) && minute_of_day >= start)
                || (day_enabled(previous_day) && minute_of_day < end)
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub camera_id: String,
    pub selected_camera_key: String,
    pub remote_nodes: Vec<RemoteNode>,
    pub recording_mode: RecordingMode,
    pub recording_schedule: RecordingSchedule,
    pub recording_root: PathBuf,
    pub legacy_recording_roots: Vec<PathBuf>,
    pub retention_days: u32,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate: u32,
    pub bind_address: String,
    pub ai_enabled: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            camera_id: String::new(),
            selected_camera_key: String::new(),
            remote_nodes: Vec::new(),
            recording_mode: RecordingMode::Continuous,
            recording_schedule: RecordingSchedule::default(),
            recording_root: PathBuf::from(r"F:\monitor"),
            legacy_recording_roots: vec![PathBuf::from(r"D:\download\Monitor")],
            retention_days: 30,
            width: 1920,
            height: 1080,
            fps: 30,
            bitrate: 4_000_000,
            bind_address: "127.0.0.1:8000".to_owned(),
            ai_enabled: false,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct RemoteNode {
    pub name: String,
    pub address: String,
}

#[derive(Clone, Debug)]
pub struct AppPaths {
    pub root: PathBuf,
    pub data: PathBuf,
    pub logs: PathBuf,
    pub settings: PathBuf,
    pub database: PathBuf,
    pub tls: PathBuf,
    pub tls_ca: PathBuf,
    pub tls_cert: PathBuf,
    pub tls_key: PathBuf,
}

impl AppPaths {
    pub fn discover() -> Result<Self> {
        let local = env::var_os("LOCALAPPDATA").context("LOCALAPPDATA is unavailable")?;
        let root = PathBuf::from(local).join("CameraMonitor");
        Ok(Self {
            data: root.join("data"),
            logs: root.join("logs"),
            settings: root.join("settings.json"),
            database: root.join("data").join("monitor.db"),
            tls: root.join("tls"),
            tls_ca: root.join("tls").join("ca.crt"),
            tls_cert: root.join("tls").join("server.crt"),
            tls_key: root.join("tls").join("server.key"),
            root,
        })
    }

    pub fn ensure(&self) -> Result<()> {
        fs::create_dir_all(&self.data)?;
        fs::create_dir_all(&self.logs)?;
        fs::create_dir_all(&self.tls)?;
        Ok(())
    }
}

pub fn load_or_create_settings(paths: &AppPaths) -> Result<Settings> {
    paths.ensure()?;
    if paths.settings.exists() {
        let raw = fs::read_to_string(&paths.settings)?;
        if let Ok(settings) = serde_json::from_str(&raw) {
            return Ok(settings);
        }
        let damaged = paths.settings.with_extension("damaged.json");
        let _ = fs::rename(&paths.settings, damaged);
    }
    let settings = Settings::default();
    save_settings_atomic(paths, &settings)?;
    Ok(settings)
}

pub fn save_settings_atomic(paths: &AppPaths, settings: &Settings) -> Result<()> {
    paths.ensure()?;
    let bytes = serde_json::to_vec_pretty(settings)?;
    AtomicFile::new(&paths.settings, AllowOverwrite).write(|file| {
        file.write_all(&bytes)?;
        file.write_all(b"\n")?;
        file.sync_all()
    })?;
    Ok(())
}

pub fn migrate_legacy(paths: &AppPaths, repository_root: &Path) -> Result<()> {
    paths.ensure()?;
    let legacy_db = repository_root.join("data").join("monitor.db");
    if !paths.database.exists() && legacy_db.exists() {
        fs::copy(&legacy_db, &paths.database)
            .with_context(|| format!("copying legacy database {}", legacy_db.display()))?;
        fs::copy(
            &legacy_db,
            paths.database.with_extension("legacy-backup.db"),
        )?;
    }
    Ok(())
}

#[derive(Clone, Debug, Serialize)]
pub struct Visit {
    pub id: i64,
    pub person_id: String,
    pub appeared_at: String,
    pub left_at: Option<String>,
    pub stay_seconds: f64,
    pub source_track_id: Option<i64>,
    pub note: String,
    pub status: &'static str,
}

#[derive(Clone)]
pub struct Repository {
    database: Arc<Mutex<PathBuf>>,
}

impl Repository {
    pub fn open(path: PathBuf) -> Result<Self> {
        let repo = Self {
            database: Arc::new(Mutex::new(path)),
        };
        repo.initialize()?;
        Ok(repo)
    }

    fn connect(&self) -> Result<Connection> {
        let path = self
            .database
            .lock()
            .expect("database path poisoned")
            .clone();
        Ok(Connection::open(path)?)
    }

    fn initialize(&self) -> Result<()> {
        let conn = self.connect()?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=3000;
             CREATE TABLE IF NOT EXISTS visit_records (
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               person_id VARCHAR(64) NOT NULL,
               source_track_id INTEGER,
               appeared_at DATETIME NOT NULL,
               left_at DATETIME,
               stay_seconds FLOAT NOT NULL DEFAULT 0,
               note VARCHAR(500) NOT NULL DEFAULT ''
             );",
        )?;
        Ok(())
    }

    pub fn list_visits(&self, limit: u32) -> Result<Vec<Visit>> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT id, person_id, appeared_at, left_at, stay_seconds, source_track_id, note
             FROM visit_records ORDER BY appeared_at DESC LIMIT ?1",
        )?;
        let rows = stmt.query_map([limit], |row| {
            let left_at: Option<String> = row.get(3)?;
            Ok(Visit {
                id: row.get(0)?,
                person_id: row.get(1)?,
                appeared_at: row.get(2)?,
                left_at: left_at.clone(),
                stay_seconds: row.get(4)?,
                source_track_id: row.get(5)?,
                note: row.get(6)?,
                status: if left_at.is_some() {
                    "已离开"
                } else {
                    "在场"
                },
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn update_visit_note(&self, id: i64, note: &str) -> Result<bool> {
        Ok(self.connect()?.execute(
            "UPDATE visit_records SET note=?1 WHERE id=?2",
            params![note, id],
        )? > 0)
    }

    pub fn delete_visit(&self, id: i64) -> Result<bool> {
        Ok(self
            .connect()?
            .execute("DELETE FROM visit_records WHERE id=?1", [id])?
            > 0)
    }

    pub fn update_person_note(&self, person_id: &str, note: &str) -> Result<usize> {
        Ok(self.connect()?.execute(
            "UPDATE visit_records SET note=?1 WHERE person_id=?2",
            params![note, person_id],
        )?)
    }

    pub fn delete_visits(&self, ids: &[i64]) -> Result<usize> {
        if ids.is_empty() {
            return Ok(0);
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let mut deleted = 0;
        {
            let mut statement = transaction.prepare("DELETE FROM visit_records WHERE id=?1")?;
            for id in ids {
                deleted += statement.execute([id])?;
            }
        }
        transaction.commit()?;
        Ok(deleted)
    }

    pub fn summary(&self) -> Result<(i64, f64)> {
        let conn = self.connect()?;
        let persons = conn.query_row(
            "SELECT COUNT(DISTINCT person_id) FROM visit_records",
            [],
            |row| row.get(0),
        )?;
        let average = conn
            .query_row("SELECT AVG(stay_seconds) FROM visit_records", [], |row| {
                row.get::<_, Option<f64>>(0)
            })?
            .unwrap_or_default();
        Ok((persons, average))
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct RecordingEntry {
    pub path: String,
    pub name: String,
    pub day: String,
    pub size_bytes: u64,
    pub modified_at: DateTime<Utc>,
}

pub fn list_recordings(settings: &Settings) -> Result<Vec<RecordingEntry>> {
    let mut roots = vec![("active", settings.recording_root.as_path())];
    roots.extend(
        settings
            .legacy_recording_roots
            .iter()
            .map(|p| ("legacy", p.as_path())),
    );
    let mut entries = Vec::new();
    for (root_id, root) in roots {
        collect_recordings(root_id, root, root, &mut entries)?;
    }
    entries.sort_by(|a, b| b.modified_at.cmp(&a.modified_at));
    Ok(entries)
}

fn collect_recordings(
    root_id: &str,
    root: &Path,
    current: &Path,
    out: &mut Vec<RecordingEntry>,
) -> Result<()> {
    if !current.exists() {
        return Ok(());
    }
    for item in fs::read_dir(current)? {
        let item = item?;
        let path = item.path();
        if path.is_dir() {
            if path.file_name().and_then(|v| v.to_str()) != Some("recovery") {
                collect_recordings(root_id, root, &path, out)?;
            }
            continue;
        }
        if path
            .extension()
            .and_then(|v| v.to_str())
            .map(|v| v.eq_ignore_ascii_case("mp4"))
            != Some(true)
            || path
                .file_name()
                .and_then(|v| v.to_str())
                .map(|v| v.ends_with(".partial.mp4"))
                == Some(true)
        {
            continue;
        }
        let metadata = path.metadata()?;
        let relative = path.strip_prefix(root)?;
        let day = relative
            .components()
            .next()
            .and_then(|v| v.as_os_str().to_str())
            .unwrap_or("")
            .to_owned();
        out.push(RecordingEntry {
            path: format!(
                "{root_id}:{}",
                relative.to_string_lossy().replace('\\', "/")
            ),
            name: path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned(),
            day,
            size_bytes: metadata.len(),
            modified_at: metadata.modified()?.into(),
        });
    }
    Ok(())
}

pub fn resolve_recording(settings: &Settings, opaque: &str) -> Result<PathBuf> {
    let (root_id, relative) = opaque.split_once(':').context("invalid recording id")?;
    let root = match root_id {
        "active" => &settings.recording_root,
        "legacy" => settings
            .legacy_recording_roots
            .first()
            .context("legacy root unavailable")?,
        _ => bail!("unknown recording root"),
    };
    let relative = Path::new(relative);
    if relative.is_absolute()
        || relative
            .components()
            .any(|c| matches!(c, std::path::Component::ParentDir))
    {
        bail!("recording path escapes root");
    }
    let path = root.join(relative);
    let canonical_root = root
        .canonicalize()
        .context("recording root is unavailable")?;
    let canonical_path = path.canonicalize().context("recording not found")?;
    if !canonical_path.starts_with(&canonical_root) || !canonical_path.is_file() {
        bail!("recording not found");
    }
    Ok(canonical_path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_native_continuous_1080p() {
        let s = Settings::default();
        assert_eq!(s.recording_mode, RecordingMode::Continuous);
        assert_eq!((s.width, s.height, s.fps), (1920, 1080, 30));
        assert_eq!(s.recording_root, PathBuf::from(r"F:\monitor"));
    }

    #[test]
    fn schedule_contains_daytime_window() {
        let schedule = RecordingSchedule {
            start: "08:30".into(),
            end: "18:00".into(),
            days: vec![1, 2, 3, 4, 5],
        };
        assert!(schedule.is_valid());
        assert!(schedule.contains(1, 8 * 60 + 30));
        assert!(schedule.contains(5, 17 * 60 + 59));
        assert!(!schedule.contains(1, 18 * 60));
        assert!(!schedule.contains(6, 10 * 60));
    }

    #[test]
    fn schedule_contains_overnight_window() {
        let schedule = RecordingSchedule {
            start: "22:00".into(),
            end: "06:00".into(),
            days: vec![7],
        };
        // 周日晚上进入时段
        assert!(schedule.contains(7, 23 * 60));
        // 周一凌晨仍属于周日的跨午夜时段
        assert!(schedule.contains(1, 5 * 60));
        assert!(!schedule.contains(1, 6 * 60));
        assert!(!schedule.contains(3, 23 * 60));
    }

    #[test]
    fn schedule_rejects_invalid_values() {
        let mut schedule = RecordingSchedule::default();
        schedule.start = "24:00".into();
        assert!(!schedule.is_valid());
        schedule.start = "08:00".into();
        schedule.days = vec![0];
        assert!(!schedule.is_valid());
        assert!(RecordingSchedule::parse_minutes("7:5") == Some(7 * 60 + 5));
        assert!(RecordingSchedule::parse_minutes("aa:bb").is_none());
    }

    #[test]
    fn rejects_parent_path() {
        let result = resolve_recording(&Settings::default(), "active:../secret.mp4");
        assert!(result.is_err());
    }

    #[test]
    fn atomic_settings_replace_existing_file() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("CameraMonitor");
        let paths = AppPaths {
            data: root.join("data"),
            logs: root.join("logs"),
            settings: root.join("settings.json"),
            database: root.join("data/monitor.db"),
            tls: root.join("tls"),
            tls_ca: root.join("tls/ca.crt"),
            tls_cert: root.join("tls/server.crt"),
            tls_key: root.join("tls/server.key"),
            root,
        };
        let mut settings = Settings::default();
        settings.bitrate = 2_000_000;
        save_settings_atomic(&paths, &settings).unwrap();
        settings.bitrate = 4_000_000;
        save_settings_atomic(&paths, &settings).unwrap();
        let loaded = load_or_create_settings(&paths).unwrap();
        assert_eq!(loaded.bitrate, 4_000_000);
        assert!(!paths.settings.with_extension("tmp").exists());
    }

    #[test]
    fn repository_bulk_operations_are_transactional() {
        let temp = tempfile::tempdir().unwrap();
        let database = temp.path().join("monitor.db");
        let repo = Repository::open(database.clone()).unwrap();
        let connection = Connection::open(database).unwrap();
        connection.execute(
            "INSERT INTO visit_records(person_id, appeared_at, stay_seconds, note) VALUES('p1','2026-01-01',1,'')",
            [],
        ).unwrap();
        connection.execute(
            "INSERT INTO visit_records(person_id, appeared_at, stay_seconds, note) VALUES('p1','2026-01-02',2,'')",
            [],
        ).unwrap();
        assert_eq!(repo.update_person_note("p1", "known").unwrap(), 2);
        let visits = repo.list_visits(10).unwrap();
        let ids: Vec<_> = visits.iter().map(|visit| visit.id).collect();
        assert!(visits.iter().all(|visit| visit.note == "known"));
        assert_eq!(repo.delete_visits(&ids).unwrap(), 2);
        assert!(repo.list_visits(10).unwrap().is_empty());
    }
}
