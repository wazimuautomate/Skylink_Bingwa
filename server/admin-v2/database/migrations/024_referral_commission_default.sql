-- Raise the referral commission default from 0% to the intended 10%.
--
-- 022_referrals.sql deliberately seeded referral_commission_bps at 0 until real
-- per-offer margins were recorded and a rate was decided. That decision is now
-- made: commission defaults to 10% of the sale (Ksh 10 on a Ksh 100 purchase),
-- still hard-capped per offer at that offer's own margin_bps, so a rate above
-- margin can never be saved regardless of this default.
--
-- Only touches the row if it is still exactly the old '0' default -- an admin
-- who already set their own rate through the settings form keeps it untouched.
UPDATE {p}settings SET svalue = '1000', updated_at = UTC_TIMESTAMP()
 WHERE skey = 'referral_commission_bps' AND svalue = '0';
