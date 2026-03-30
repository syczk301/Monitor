from __future__ import annotations

import numpy as np

from app.core.face import ReIDRegistry, TemporalIdentityMemory


def _normalize(vec: np.ndarray) -> np.ndarray:
    vec = vec.astype(np.float32)
    return vec / np.linalg.norm(vec)


def test_reid_registry_uses_person_centroid_for_stable_matching() -> None:
    registry = ReIDRegistry(threshold=0.95)
    base = np.ones((128,), dtype=np.float32)
    drift = np.zeros((128,), dtype=np.float32)
    drift[0] = 0.55

    first = _normalize(base + drift)
    second = _normalize(base - drift)
    query = _normalize(base)

    person_id = registry.resolve_person_id(first)
    registry.update_feature(person_id, second)

    assert registry.resolve_person_id(query) == person_id


def test_temporal_identity_memory_reconnects_recent_track() -> None:
    memory = TemporalIdentityMemory(
        similarity_threshold=0.75,
        window_ms=5_000,
        max_distance_px=120,
    )
    base = _normalize(np.linspace(0.1, 1.0, 128, dtype=np.float32))
    query = _normalize(base + 0.01)

    memory.remember(
        track_id=7,
        person_id="person-123",
        bbox=(100, 100, 180, 280),
        feature=base,
        ts_ms=1_000,
    )

    matched = memory.match(
        track_id=9,
        bbox=(112, 108, 192, 288),
        feature=query,
        ts_ms=2_000,
        active_track_ids={9},
    )

    assert matched == "person-123"


def test_temporal_identity_memory_rejects_far_or_expired_track() -> None:
    memory = TemporalIdentityMemory(
        similarity_threshold=0.75,
        window_ms=500,
        max_distance_px=80,
    )
    base = _normalize(np.linspace(0.1, 1.0, 128, dtype=np.float32))

    memory.remember(
        track_id=3,
        person_id="person-xyz",
        bbox=(50, 50, 110, 190),
        feature=base,
        ts_ms=1_000,
    )

    far_match = memory.match(
        track_id=4,
        bbox=(260, 260, 320, 400),
        feature=base,
        ts_ms=1_200,
        active_track_ids={4},
    )
    expired_match = memory.match(
        track_id=4,
        bbox=(60, 60, 120, 200),
        feature=base,
        ts_ms=1_700,
        active_track_ids={4},
    )

    assert far_match is None
    assert expired_match is None


def test_reid_registry_tracks_dirty_templates_and_can_reload() -> None:
    registry = ReIDRegistry(threshold=0.5)
    feature = _normalize(np.linspace(0.1, 1.0, 128, dtype=np.float32))

    person_id = registry.resolve_person_id(feature)
    assert registry.get_dirty_person_ids() == {person_id}

    snapshot = registry.snapshot_templates(registry.get_dirty_person_ids())
    registry.mark_persisted({person_id})
    assert registry.get_dirty_person_ids() == set()

    reloaded = ReIDRegistry(threshold=0.5)
    reloaded.load_templates(snapshot)
    assert reloaded.resolve_person_id(feature) == person_id
