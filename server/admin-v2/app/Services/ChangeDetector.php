<?php
/**
 * Field-level change detection between two published-shape snapshots.
 *
 * This class exists to answer one question honestly: **what actually changed?**
 *
 * The old preview inferred "modified" from row timestamps and row_version counters, so
 * opening an offer and pressing Save without editing anything made the app look like it
 * had a pending update. Nothing here ever looks at a timestamp, an updated_at column or a
 * row_version. Two items are the same when their VALUES canonically encode identically —
 * and when they are the same, this class returns an EMPTY field list, which is what makes
 * "Nothing to publish" trustworthy.
 *
 * Comparison is recursive: nested keys are flattened with a dot ('variations.0.body') and
 * lists are compared by index, so an operator sees "Variation #2 body" rather than a wall
 * of JSON.
 */

namespace App\Services;

final class ChangeDetector
{
    /**
     * Snapshot section => the heading an operator reads. Also fixes the order every
     * grouped view (Preview, publish review, release detail) renders modules in.
     */
    public const MODULES = [
        'offers'        => 'Offers',
        'categories'    => 'Categories',
        'billboards'    => 'Billboards',
        'support'       => 'Payment & support details',
        'appConfig'     => 'App configuration',
        'featureFlags'  => 'Feature flags',
        'version'       => 'App update rule',
    ];

    /** entity_type recorded on a change item => the snapshot section it belongs to. */
    public const ENTITY_MODULES = [
        'offer'        => 'offers',
        'category'     => 'categories',
        'billboard'    => 'billboards',
        'support'      => 'support',
        'appConfig'    => 'appConfig',
        'app_config'   => 'appConfig',   // release rows written before the rename
        'featureFlags' => 'featureFlags',
        'version'      => 'version',
    ];

    /**
     * Human field names per entity type. A field with no entry here still renders — it is
     * humanised from its key — so adding a snapshot field never produces a blank label.
     */
    private const FIELD_LABELS = [
        'offer' => [
            'id' => 'Offer id', 'category' => 'Category', 'name' => 'Name', 'price' => 'Price',
            'validity' => 'Validity', 'band' => 'Band', 'dailyRule' => 'Daily limit',
            'policy' => 'Daily limit policy', 'maxPerDay' => 'Maximum per day',
            'commercialTag' => 'Commercial tag', 'offlineEligible' => 'Offline purchase',
            'restrictions' => 'Restrictions', 'sortHint' => 'Sort order',
        ],
        'category' => [
            'id' => 'Category key', 'label' => 'Label', 'description' => 'Description',
            'accent' => 'Accent', 'sortOrder' => 'Sort order',
        ],
        'billboard' => [
            'id' => 'Billboard id', 'kind' => 'Kind', 'priority' => 'Priority',
            'displayOrder' => 'Display order', 'linkedOfferId' => 'Linked offer', 'tag' => 'Tag',
            'headline' => 'Headline', 'body' => 'Body', 'ctaLabel' => 'Button label',
            'ctaDestination' => 'Button destination', 'mediaType' => 'Media type',
            'imageUrl' => 'Image', 'thumbUrl' => 'Thumbnail', 'altText' => 'Alt text',
            'targetAction' => 'Tap action', 'clickUrl' => 'Tap URL',
            'internalAction' => 'Internal screen', 'targetCategory' => 'Target category',
            'audienceRule' => 'Audience', 'frequencyCap' => 'Frequency cap',
            'startsAt' => 'Starts', 'endsAt' => 'Ends',
        ],
        'support' => [
            'tillNumber' => 'Till number', 'paybillNumber' => 'Paybill number',
            'supportNumber' => 'Support number', 'supportWhatsapp' => 'Support WhatsApp',
            'offlineSelfInstructions' => 'Offline instructions (own number)',
            'offlineOtherInstructions' => 'Offline instructions (another number)',
            'supportBanner' => 'Support banner', 'workingHours' => 'Working hours',
        ],
        'appConfig' => [
            'maintenanceMode' => 'Maintenance mode', 'maintenanceMessage' => 'Maintenance message',
            'syncIntervalMinutes' => 'Sync interval (minutes)',
            'generalSupportMessage' => 'General support message',
        ],
        'version' => [
            'latestVersionCode' => 'Latest version code', 'latestVersionName' => 'Latest version name',
            'minSupportedVersionCode' => 'Minimum supported version code', 'mandatory' => 'Forced update',
            'updateSource' => 'Update source', 'playStoreUrl' => 'Play Store URL', 'apkUrl' => 'APK URL',
            'apkSha256' => 'APK SHA-256', 'rolloutPercent' => 'Rollout percent',
            'releaseNotes' => 'Release notes',
        ],
    ];

    /* ------------------------------------------------------------------ labels */

    public static function moduleLabel(string $module): string
    {
        return self::MODULES[$module] ?? self::humanise($module);
    }

    /** The snapshot section an entity_type belongs to. */
    public static function moduleForEntity(string $entityType): string
    {
        return self::ENTITY_MODULES[$entityType] ?? $entityType;
    }

    /**
     * Human label for a (possibly dotted) field path.
     * 'price' => 'Price'; 'variations.1.body' => 'Wording variations #2 · Body'.
     */
    public static function fieldLabel(string $entityType, string $field): string
    {
        $parts = explode('.', $field);
        $head = (string) array_shift($parts);
        $map = self::FIELD_LABELS[$entityType] ?? [];
        $label = $map[$head] ?? self::humanise($head);
        foreach ($parts as $part) {
            if ($part !== '' && ctype_digit($part)) {
                $label .= ' #' . ((int) $part + 1);
            } else {
                $label .= ' · ' . self::humanise((string) $part);
            }
        }
        return $label;
    }

    /* -------------------------------------------------------------- comparison */

    /**
     * Recursive, value-only comparison of two items.
     *
     * @return array<int,array{field:string,label:string,from:mixed,to:mixed}>
     *         EMPTY when the two items carry identical values — the guarantee the whole
     *         preview rests on.
     */
    public static function compareItems(string $entityType, array $old, array $new): array
    {
        $oldFlat = [];
        $newFlat = [];
        self::flatten(self::normalise($old), '', $oldFlat);
        self::flatten(self::normalise($new), '', $newFlat);

        // New-snapshot order first (that is the order an operator reads the form in),
        // then any key that only exists in the published version.
        $keys = array_keys($newFlat + $oldFlat);

        $fields = [];
        foreach ($keys as $key) {
            $key = (string) $key;
            $inOld = array_key_exists($key, $oldFlat);
            $inNew = array_key_exists($key, $newFlat);
            $from = $inOld ? $oldFlat[$key] : null;
            $to   = $inNew ? $newFlat[$key] : null;

            // A container key that is empty on one side and simply absent on the other is
            // not a value change — any real difference is reported through its indexed
            // children ('daysOfWeek.0'), so reporting the parent too would be noise.
            if (!$inOld && $to === []) {
                continue;
            }
            if (!$inNew && $from === []) {
                continue;
            }
            if (self::sameValue($from, $to)) {
                continue;
            }
            $fields[] = [
                'field' => $key,
                'label' => self::fieldLabel($entityType, $key),
                'from'  => $from,
                'to'    => $to,
            ];
        }
        return $fields;
    }

    /** True when two leaf values are identical after canonical encoding. */
    public static function sameValue($a, $b): bool
    {
        return self::canonical($a) === self::canonical($b);
    }

    /* ------------------------------------------------------------- presentation */

    /**
     * One line an operator can read without opening the item.
     * @param array<int,array{field:string,label:string,from:mixed,to:mixed}> $fields
     */
    public static function summarise(string $changeType, string $label, array $fields): string
    {
        if ($changeType === 'added') {
            return 'Added ' . $label;
        }
        if ($changeType === 'removed') {
            return 'Removed ' . $label;
        }
        if ($fields === []) {
            return $label . ' changed';
        }
        $names = [];
        foreach (array_slice($fields, 0, 4) as $f) {
            $names[] = (string) ($f['label'] ?? $f['field'] ?? '') . ' updated';
        }
        $extra = count($fields) - count($names);
        return implode(', ', $names) . ($extra > 0 ? ' and ' . $extra . ' more' : '');
    }

    /**
     * Short display text for a diff value. The optional $field lets an obvious money
     * field render as KSh without this class having to guess from the number alone.
     */
    public static function displayValue($value, string $field = ''): string
    {
        if ($value === null) {
            return '(none)';
        }
        if (is_bool($value)) {
            return $value ? 'Yes' : 'No';
        }
        if (is_array($value) || is_object($value)) {
            $arr = self::normalise($value);
            if ($arr === []) {
                return '(empty)';
            }
            $json = (string) json_encode($arr, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
            return mb_strlen($json) > 90 ? (mb_substr($json, 0, 87) . '…') : $json;
        }
        if (is_string($value) && trim($value) === '') {
            return '(empty)';
        }
        if ($field !== '' && is_numeric($value) && self::isMoneyField($field)) {
            return 'KSh ' . number_format((float) $value, 0);
        }
        $text = (string) $value;
        return mb_strlen($text) > 120 ? (mb_substr($text, 0, 117) . '…') : $text;
    }

    /* ------------------------------------------------------------------ internals */

    /** Last path segment is a price-like field. */
    private static function isMoneyField(string $field): bool
    {
        $parts = explode('.', $field);
        $last = strtolower((string) end($parts));
        return $last === 'price' || substr($last, -5) === 'price';
    }

    /**
     * stdClass => array, and an empty object becomes an empty array. Without this an
     * empty `captures` object (working state) and the `[]` it decodes back to (published
     * snapshot) would look different on every single preview.
     */
    private static function normalise($value)
    {
        if (is_object($value)) {
            $value = (array) $value;
        }
        if (is_array($value)) {
            if ($value === []) {
                return [];
            }
            $out = [];
            foreach ($value as $k => $v) {
                $out[$k] = self::normalise($v);
            }
            return $out;
        }
        return $value;
    }

    /** Flatten a normalised structure into 'dot.path' => leaf value. */
    private static function flatten($value, string $prefix, array &$out): void
    {
        if (is_array($value) && $value !== []) {
            foreach ($value as $k => $v) {
                $key = $prefix === '' ? (string) $k : ($prefix . '.' . $k);
                self::flatten($v, $key, $out);
            }
            return;
        }
        if ($prefix === '') {
            return; // an entirely empty item contributes no fields
        }
        $out[$prefix] = $value;
    }

    private static function canonical($value): string
    {
        return (string) json_encode(self::normalise($value), JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }

    /** 'syncIntervalMinutes' => 'Sync interval minutes'; 'deep_link' => 'Deep link'. */
    private static function humanise(string $key): string
    {
        if ($key === '') {
            return '';
        }
        $spaced = preg_replace_callback(
            '/(?<!^)[A-Z]/',
            static fn(array $m): string => ' ' . mb_strtolower($m[0]),
            str_replace(['_', '-'], ' ', $key)
        );
        $spaced = trim((string) preg_replace('/\s+/', ' ', (string) $spaced));
        if ($spaced === '') {
            return $key;
        }
        return mb_strtoupper(mb_substr($spaced, 0, 1)) . mb_substr($spaced, 1);
    }
}
