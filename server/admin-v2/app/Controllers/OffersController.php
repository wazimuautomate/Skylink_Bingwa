<?php
/**
 * Offers CRUD with safe archive/restore/duplicate/delete and a per-offer
 * unpublished-change indicator. Changes are drafts until published (see PublishController).
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Csrf;
use App\Core\Flash;
use App\Core\Request;
use App\Core\Validator;
use App\Repositories\OfferRepository;
use App\Support\Csv;

final class OffersController extends Controller
{
    public function index(Request $request): void
    {
        $this->guard('offers.view');
        $filters = [
            'q' => (string) $request->get('q', ''),
            'category' => (string) $request->get('category', ''),
            'status' => (string) $request->get('status', ''),
            'rule' => (string) $request->get('rule', ''),
            'band' => (string) $request->get('band', ''),
            'min' => $request->get('min', ''),
            'max' => $request->get('max', ''),
        ];
        $offers = OfferRepository::search($filters);
        $published = OfferRepository::publishedById();
        $this->view('offers/index', [
            'activeNav' => 'offers', 'pageTitle' => 'Offers',
            'offers' => $offers, 'filters' => $filters, 'published' => $published,
        ]);
    }

    public function create(Request $request): void
    {
        $this->guard('offers.create');
        $this->view('offers/form', [
            'activeNav' => 'offers', 'pageTitle' => 'Add offer',
            'offer' => null, 'isNew' => true,
        ]);
    }

    public function edit(Request $request, string $id): void
    {
        $this->guard('offers.edit');
        $offer = OfferRepository::find($id);
        if (!$offer) {
            Flash::error('Offer not found.');
            $this->redirect('/offers');
        }
        $this->view('offers/form', [
            'activeNav' => 'offers', 'pageTitle' => 'Edit offer',
            'offer' => $offer, 'isNew' => false,
        ]);
    }

    public function save(Request $request): void
    {
        Csrf::check($request);
        $isNew = $request->post('is_new') === '1';
        $this->guard($isNew ? 'offers.create' : 'offers.edit');

        // The offer ID is generated automatically for a new offer and is immutable
        // afterwards (the edit form submits it back in a hidden field).
        $input = [
            'offer_id' => $isNew ? '' : strtolower(trim((string) $request->post('offer_id', ''))),
            'category' => (string) $request->post('category', 'DATA'),
            'name' => trim((string) $request->post('name', '')),
            'price' => $request->post('price', ''),
            'validity' => trim((string) $request->post('validity', '')),
            'band' => (string) $request->post('band', 'Daily'),
            'daily_rule' => (string) $request->post('daily_rule', 'MULTIPLE_PER_DAY'),
            'max_per_day' => $request->post('max_per_day', '') === '' ? null : (int) $request->post('max_per_day'),
            // Safaricom's time-of-day selling window, kept as Nairobi wall-clock
            // "HH:MM:00" (never UTC — see migration 018). Blank = no restriction.
            'available_from' => $this->toTime($request->post('available_from')),
            'available_to' => $this->toTime($request->post('available_to')),
            'commercial_tag' => trim((string) $request->post('commercial_tag', '')),
            'offline_eligible' => $request->post('offline_eligible') ? 1 : 0,
            'restrictions' => trim((string) $request->post('restrictions', '')),
            'status' => $request->post('active') ? 'active' : 'draft',
            'starts_at' => $this->toUtc($request->post('starts_at')),
            'ends_at' => $this->toUtc($request->post('ends_at')),
            'sort_hint' => (int) $request->post('sort_hint', 0),
            // Referral economics, in basis points (300 = 3.00%). Blank means
            // "fall back to the category or global rate" rather than zero.
            'commission_bps' => $request->post('commission_bps', '') === '' ? null : (int) $request->post('commission_bps'),
            'margin_bps' => $request->post('margin_bps', '') === '' ? null : (int) $request->post('margin_bps'),
        ];

        $v = Validator::make($input);
        $rules = [
            'category' => 'required|in:' . implode(',', OfferRepository::CATEGORIES),
            'name' => 'required|max:80',
            'price' => 'required|int|min:1|max:100000',
            'validity' => 'required|max:48',
            'daily_rule' => 'required|in:' . implode(',', array_keys(OfferRepository::RULES)),
        ];
        $v->validate($rules);
        if ($input['daily_rule'] === 'MAX_PER_RECIPIENT_PER_DAY' && ($input['max_per_day'] === null || $input['max_per_day'] < 1)) {
            $v->add('max_per_day', 'Set a maximum count for this rule.');
        }
        // A window needs both ends: one end alone is ambiguous to the customer
        // ("from 5pm" until when?) and the app would have to guess a closing time.
        if (($input['available_from'] === null) !== ($input['available_to'] === null)) {
            $v->add('available_from', 'Set both the opening and the closing time, or leave both blank.');
        }
        if ($input['available_from'] !== null && $input['available_from'] === $input['available_to']) {
            $v->add('available_from', 'The opening and closing times cannot be the same.');
        }
        if (!$isNew && !OfferRepository::exists($input['offer_id'])) {
            $v->add('offer_id', 'Offer not found.');
        }
        // A referral commission above the real margin loses money on every referred
        // sale, and the loss grows the better the referral programme works. Refused
        // here rather than trusting whoever fills the form to remember.
        if ($input['commission_bps'] !== null && $input['margin_bps'] !== null
            && $input['commission_bps'] > $input['margin_bps']) {
            $v->add('commission_bps', 'Commission cannot be higher than this offer\'s margin.');
        }
        if ($v->fails()) {
            Flash::error('Please correct the highlighted fields.');
            Flash::keepOld(array_merge($input, ['_errors' => $v->firstErrors()]));
            $this->redirect($isNew ? '/offers/new' : '/offers/' . $input['offer_id'] . '/edit');
        }

        // Validation passed → assign the generated id for a new offer.
        if ($isNew) {
            $input['offer_id'] = OfferRepository::nextOfferId($input['category']);
        }

        $expectedVersion = $isNew ? null : (int) $request->post('row_version', 0);
        $before = $isNew ? null : OfferRepository::find($input['offer_id']);
        $result = OfferRepository::save($input, $isNew, $expectedVersion);
        if (!$result['ok'] && $result['conflict']) {
            Flash::error('Someone else edited this offer while you were working. Reopen it to see the latest version.');
            $this->redirect('/offers/' . $input['offer_id'] . '/edit');
        }

        Audit::log([
            'action' => $isNew ? 'offer.create' : 'offer.update',
            'entity_type' => 'offer', 'entity_id' => $input['offer_id'],
            'before' => $before, 'after' => OfferRepository::find($input['offer_id']),
        ]);
        Flash::success($isNew ? 'Offer created as a draft change. Publish to push it to the app.' : 'Offer updated. Publish to apply.');
        $this->redirect('/offers');
    }

    public function duplicate(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('offers.create');
        $src = OfferRepository::find($id);
        if (!$src) {
            Flash::error('Offer not found.');
            $this->redirect('/offers');
        }
        // New auto-generated id + a draft copy; never overwrites the source.
        $newId = OfferRepository::nextOfferId($src['category']);
        OfferRepository::save([
            'offer_id' => $newId, 'category' => $src['category'], 'name' => $src['name'], 'price' => $src['price'],
            'validity' => $src['validity'], 'band' => $src['band'], 'daily_rule' => $src['daily_rule'],
            'max_per_day' => $src['max_per_day'],
            'available_from' => $src['available_from'] ?? null, 'available_to' => $src['available_to'] ?? null,
            'commercial_tag' => $src['commercial_tag'],
            'offline_eligible' => $src['offline_eligible'], 'restrictions' => $src['restrictions'],
            'status' => 'draft', 'starts_at' => $src['starts_at'], 'ends_at' => $src['ends_at'],
            'sort_hint' => (int) $src['sort_hint'] + 1,
        ], true);
        Audit::log(['action' => 'offer.duplicate', 'entity_type' => 'offer', 'entity_id' => $newId, 'after' => ['from' => $id]]);
        Flash::success("Duplicated as draft “{$newId}”.");
        $this->redirect('/offers/' . $newId . '/edit');
    }

    public function archive(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('offers.archive');
        if (!OfferRepository::find($id)) {
            Flash::error('Offer not found.'); $this->redirect('/offers');
        }
        OfferRepository::setStatus($id, 'archived', 'archive');
        Audit::log(['action' => 'offer.archive', 'entity_type' => 'offer', 'entity_id' => $id]);
        Flash::success('Offer archived. Publish to remove it from the app.');
        $this->redirect('/offers');
    }

    public function restore(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('offers.archive');
        OfferRepository::setStatus($id, 'active', 'restore');
        Audit::log(['action' => 'offer.restore', 'entity_type' => 'offer', 'entity_id' => $id]);
        Flash::success('Offer restored to active.');
        $this->redirect('/offers');
    }

    public function delete(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('offers.delete');
        $offer = OfferRepository::find($id);
        if (!$offer) {
            Flash::error('Offer not found.'); $this->redirect('/offers');
        }
        // Safety: never hard-delete an offer referenced by payments or already published.
        if (OfferRepository::referencedByPayments($id)) {
            Flash::error('This offer is referenced by payments and can only be archived, not deleted.');
            $this->redirect('/offers');
        }
        if ($offer['status'] !== 'draft') {
            Flash::error('Only draft offers can be deleted. Archive active offers instead.');
            $this->redirect('/offers');
        }
        OfferRepository::delete($id);
        Audit::log(['action' => 'offer.delete', 'entity_type' => 'offer', 'entity_id' => $id, 'before' => $offer]);
        Flash::success('Draft offer deleted.');
        $this->redirect('/offers');
    }

    public function exportCsv(Request $request): void
    {
        $this->guard('offers.view');
        $offers = OfferRepository::search([
            'q' => (string) $request->get('q', ''), 'category' => (string) $request->get('category', ''),
            'status' => (string) $request->get('status', ''),
        ]);
        Csv::stream('skylinkbingwa-offers.csv',
            ['offer_id', 'category', 'name', 'price', 'validity', 'band', 'daily_rule',
             'available_from', 'available_to', 'offline_eligible', 'status'],
            array_map(fn($o) => [
                $o['offer_id'], $o['category'], $o['name'], $o['price'], $o['validity'],
                $o['band'], $o['daily_rule'],
                OfferRepository::hhmm($o['available_from'] ?? null),
                OfferRepository::hhmm($o['available_to'] ?? null),
                $o['offline_eligible'] ? 'yes' : 'no', $o['status'],
            ], $offers)
        );
    }

    /* helpers */

    /**
     * A "HH:MM" from the time input as the "HH:MM:00" stored in the TIME column,
     * or null for "no restriction on this end". Deliberately NOT converted to UTC:
     * a selling window is a Nairobi wall-clock fact the customer reads back.
     */
    private function toTime($value): ?string
    {
        $text = trim((string) $value);
        if ($text === '') {
            return null;
        }
        if (!preg_match('/^(\d{1,2}):(\d{2})/', $text, $m)) {
            return null;
        }
        $h = (int) $m[1];
        $min = (int) $m[2];
        if ($h < 0 || $h > 23 || $min < 0 || $min > 59) {
            return null;
        }
        return sprintf('%02d:%02d:00', $h, $min);
    }

    private function toUtc($local): ?string
    {
        $local = trim((string) $local);
        if ($local === '') {
            return null;
        }
        try {
            return (new \DateTimeImmutable($local, new \DateTimeZone('Africa/Nairobi')))
                ->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d H:i:s');
        } catch (\Throwable $e) {
            return null;
        }
    }
}
