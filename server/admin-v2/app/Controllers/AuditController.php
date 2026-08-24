<?php
/**
 * Append-only audit log viewer. Read-only: this UI never updates or deletes audit rows.
 *
 * Filters by module, actor, action, entity type/id, result, publish version and date
 * range, plus a free-text search over action / entity id / reason. The CSV export applies
 * exactly the same filters, so what an operator exports is what they were looking at.
 *
 * Values were already masked at WRITE time by App\Core\Audit::mask — nothing here undoes
 * that, and anything that looks like a subscriber number is masked again for display.
 */

namespace App\Controllers;

use App\Core\Database;
use App\Core\Request;
use App\Support\Csv;

final class AuditController extends Controller
{
    private const PER_PAGE = 40;

    private function table(): string { return Database::table('audit_logs'); }

    /** The filter set, read identically by the page and the export. */
    private function filters(Request $request): array
    {
        return [
            'q'           => trim((string) $request->get('q', '')),
            'module'      => (string) $request->get('module', ''),
            'actor'       => (string) $request->get('actor', ''),
            'action'      => (string) $request->get('action', ''),
            'entity_type' => (string) $request->get('entity_type', ''),
            'entity_id'   => (string) $request->get('entity_id', ''),
            'entity'      => (string) $request->get('entity', ''),   // legacy combined filter
            'success'     => (string) $request->get('success', ''),  // '', '1', '0'
            'from'        => (string) $request->get('from', ''),
            'to'          => (string) $request->get('to', ''),
            'version'     => (string) $request->get('version', ''),
        ];
    }

    public function index(Request $request): void
    {
        $this->guard('audit.view');
        $f = $this->filters($request);
        [$where, $params] = $this->buildWhere($f);

        $page = max(1, (int) $request->get('page', 1));
        $per = self::PER_PAGE;
        $total = (int) (Database::scalar('SELECT COUNT(*) FROM ' . $this->table() . " {$where}", $params) ?? 0);
        $rows = Database::fetchAll(
            'SELECT * FROM ' . $this->table() . " {$where} ORDER BY created_at DESC, id DESC LIMIT {$per} OFFSET " . (($page - 1) * $per),
            $params
        );

        $this->view('audit/index', [
            'activeNav' => 'audit', 'pageTitle' => 'Audit log',
            'rows' => $rows, 'filters' => $f, 'page' => $page, 'total' => $total, 'per' => $per,
            'actions'     => self::distinct('action'),
            'modules'     => self::distinct('module'),
            'entityTypes' => self::distinct('entity_type'),
        ]);
    }

    public function show(Request $request, string $id): void
    {
        $this->guard('audit.view');
        $row = Database::fetch('SELECT * FROM ' . $this->table() . ' WHERE id = ?', [(int) $id]);
        if (!$row) { $this->redirect('/audit'); }

        $this->view('audit/show', [
            'activeNav' => 'audit', 'pageTitle' => 'Audit entry',
            'row'    => $row,
            'diff'   => self::decode($row['diff_json'] ?? null),
            'before' => self::decode($row['before_json'] ?? null),
            'after'  => self::decode($row['after_json'] ?? null),
        ]);
    }

    public function exportCsv(Request $request): void
    {
        $this->guard('audit.view');
        [$where, $params] = $this->buildWhere($this->filters($request));
        $rows = Database::fetchAll('SELECT * FROM ' . $this->table() . " {$where} ORDER BY created_at DESC LIMIT 5000", $params);
        Csv::stream('skylinkbingwa-audit.csv',
            ['time_utc', 'time_nairobi', 'actor', 'role', 'module', 'action', 'entity_type', 'entity_id', 'version', 'ip', 'success'],
            array_map(fn($r) => [
                $r['created_at'], fmt_nairobi($r['created_at'], 'Y-m-d H:i'),
                $r['actor_name'], $r['actor_role'], $r['module'] ?? '', $r['action'], $r['entity_type'], $r['entity_id'],
                $r['release_version'], $r['ip'], $r['success'] ? 'ok' : 'fail',
            ], $rows)
        );
    }

    /* ------------------------------------------------------------------ display */

    /**
     * Render an audit value for a human. Secrets are already masked at write time; this
     * additionally masks anything stored under a subscriber-number field so a full phone
     * number is never printed on screen or in a shared screenshot.
     */
    public static function displayValue(string $field, $value): string
    {
        if ($value === null) {
            return '(none)';
        }
        if (is_bool($value)) {
            return $value ? 'Yes' : 'No';
        }
        if (is_array($value) || is_object($value)) {
            $json = (string) json_encode($value, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
            return mb_strlen($json) > 160 ? (mb_substr($json, 0, 157) . '…') : $json;
        }
        $text = (string) $value;
        if ($text === '') {
            return '(empty)';
        }
        if (self::isSubscriberField($field) && preg_match('/^\+?\d[\d\s\-]{7,17}$/', $text) === 1) {
            return str_mask_phone($text);
        }
        return mb_strlen($text) > 200 ? (mb_substr($text, 0, 197) . '…') : $text;
    }

    /** Fields that may hold a customer's own number — never printed in full. */
    private static function isSubscriberField(string $field): bool
    {
        return preg_match('/(phone|msisdn|whatsapp|mobile|payer|recipient|customer_number|support_number)/i', $field) === 1;
    }

    /* ----------------------------------------------------------------- internals */

    private static function decode($json): array
    {
        $decoded = json_decode((string) $json, true);
        return is_array($decoded) ? $decoded : [];
    }

    /** Distinct non-empty values of a column, for the filter dropdowns. */
    private static function distinct(string $column): array
    {
        $allowed = ['action', 'module', 'entity_type'];
        if (!in_array($column, $allowed, true)) {
            return [];
        }
        $rows = Database::fetchAll(
            "SELECT DISTINCT {$column} AS v FROM " . Database::table('audit_logs')
            . " WHERE {$column} <> '' ORDER BY {$column}"
        );
        return array_map(static fn($r) => (string) $r['v'], $rows);
    }

    private function buildWhere(array $f): array
    {
        $c = [];
        $p = [];
        if (($f['q'] ?? '') !== '') {
            $c[] = '(action LIKE ? OR entity_id LIKE ? OR reason LIKE ?)';
            $like = '%' . $f['q'] . '%';
            $p[] = $like; $p[] = $like; $p[] = $like;
        }
        if (($f['module'] ?? '') !== '')      { $c[] = 'module = ?'; $p[] = $f['module']; }
        if (($f['actor'] ?? '') !== '')       { $c[] = 'actor_name LIKE ?'; $p[] = '%' . $f['actor'] . '%'; }
        if (($f['action'] ?? '') !== '')      { $c[] = 'action = ?'; $p[] = $f['action']; }
        if (($f['entity_type'] ?? '') !== '') { $c[] = 'entity_type = ?'; $p[] = $f['entity_type']; }
        if (($f['entity_id'] ?? '') !== '')   { $c[] = 'entity_id LIKE ?'; $p[] = '%' . $f['entity_id'] . '%'; }
        if (($f['entity'] ?? '') !== '')      { $c[] = '(entity_type LIKE ? OR entity_id LIKE ?)'; $p[] = '%' . $f['entity'] . '%'; $p[] = '%' . $f['entity'] . '%'; }
        if (($f['success'] ?? '') !== '')     { $c[] = 'success = ?'; $p[] = (int) $f['success'] === 1 ? 1 : 0; }
        if (($f['from'] ?? '') !== '')        { $c[] = 'created_at >= ?'; $p[] = $f['from'] . ' 00:00:00'; }
        if (($f['to'] ?? '') !== '')          { $c[] = 'created_at <= ?'; $p[] = $f['to'] . ' 23:59:59'; }
        if (($f['version'] ?? '') !== '')     { $c[] = 'release_version = ?'; $p[] = (int) $f['version']; }
        return [$c ? ('WHERE ' . implode(' AND ', $c)) : '', $p];
    }
}
