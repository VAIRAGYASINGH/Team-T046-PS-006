"""Deterministic 12-digit Retrieval Reference Number (RRN) Generator."""

from typing import Dict, Optional


class RRNGenerator:
    """Generates and tracks deterministic 12-digit numeric RRNs."""

    def __init__(self, start_seq: int = 1):
        self._start_seq = start_seq
        self._current_seq = start_seq
        self._txn_to_rrn: Dict[str, str] = {}
        self._rrn_to_txn: Dict[str, str] = {}

    def next_rrn(self) -> str:
        """Generate next sequential 12-digit RRN."""
        rrn = f"{self._current_seq:012d}"
        self._current_seq += 1
        return rrn

    def get_or_create_rrn(self, txn_id: str) -> str:
        """Return existing RRN for txn_id, or generate next deterministic 12-digit RRN."""
        if txn_id in self._txn_to_rrn:
            return self._txn_to_rrn[txn_id]

        rrn = f"{self._current_seq:012d}"
        self._current_seq += 1
        self._txn_to_rrn[txn_id] = rrn
        self._rrn_to_txn[rrn] = txn_id
        return rrn

    def get_rrn(self, txn_id: str) -> Optional[str]:
        """Lookup existing RRN for txn_id without creating a new one."""
        return self._txn_to_rrn.get(txn_id)

    def reset(self, start_seq: Optional[int] = None) -> None:
        """Reset sequence and cache for reproducible test runs."""
        if start_seq is not None:
            self._start_seq = start_seq
        self._current_seq = self._start_seq
        self._txn_to_rrn.clear()
        self._rrn_to_txn.clear()
