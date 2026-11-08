use super::*;
impl<T: HttpTransport + Send + 'static> WalletEngine<T> {
    pub async fn prepare(
        &mut self,
        destination: &str,
        xmr: &str,
    ) -> Result<PaymentReview, EngineError> {
        self.prepared = None;
        self.preparation.reset();
        self.preparation.set_stage(PreparationStage::CheckingChain);
        if xmr.len() > 32 {
            return Err(EngineError::Payment);
        }
        let amount = amount::parse_xmr(xmr).map_err(|_| EngineError::Payment)?;
        if amount == 0 {
            return Err(EngineError::Payment);
        }
        self.prepare_payment(destination, Some(amount)).await
    }
    pub async fn prepare_max(&mut self, destination: &str) -> Result<PaymentReview, EngineError> {
        self.prepare_payment(destination, None).await
    }
    async fn prepare_payment(
        &mut self,
        destination: &str,
        requested: Option<u64>,
    ) -> Result<PaymentReview, EngineError> {
        self.prepared = None;
        // Free the session connection for payment reads instead of a scan download.
        self.prefetch = None;
        self.preparation.reset();
        self.preparation.set_stage(PreparationStage::CheckingChain);
        if self.spend.is_none() {
            return Err(EngineError::ViewOnly);
        }
        if self.state.payments.iter().any(|p| p.mined_height.is_none()) {
            return Err(EngineError::Pending);
        }
        if destination.len() > 196 {
            return Err(EngineError::Payment);
        }
        let recipient =
            MoneroAddress::from_str(self.network, destination).map_err(|_| EngineError::Payment)?;
        if matches!(recipient.kind(), AddressType::Featured { .. })
            || [recipient.spend(), recipient.view()]
                .iter()
                .any(|p| (*p).into().is_identity() || !(*p).into().is_torsion_free())
        {
            return Err(EngineError::Payment);
        }
        let tip = self.check_tip().await?;
        let mut available = vec![];
        for (key, r) in &self.state.outputs {
            let o = decode_output(r)?;
            if o.commitment().amount > 0
                && r.spent_height.is_none()
                && !self.state.reserved(key)
                && self.spendable(r, &o)
            {
                available.push((key.clone(), o));
            }
        }
        if available.is_empty() {
            return Err(EngineError::Funds);
        }
        if requested.is_none() && available.len() > 16 {
            return Err(EngineError::InputLimit);
        }
        let available_total = available.iter().try_fold(0u64, |sum, (_, o)| {
            sum.checked_add(o.commitment().amount)
                .ok_or(EngineError::Verification)
        })?;
        if requested.is_some_and(|a| a >= available_total) {
            return Err(EngineError::Funds);
        }
        self.preparation.set_stage(PreparationStage::FetchingFee);
        let rate = self
            .rpc()?
            .fee_rate(FeePriority::Normal, MAX_FEE_PER_WEIGHT)
            .await
            .map_err(|e| match e {
                monero_wallet::interface::FeeError::InterfaceError(e) => EngineError::node_error(e),
                _ => EngineError::FeeLimit,
            })?;
        self.preparation
            .set_stage(PreparationStage::FetchingDistribution);
        let counts = self.distribution_for_payment(tip).await?;
        let decoys = decoys::CachedDecoys {
            rpc: self.rpc()?,
            height: (self.state.next_height - 1) as usize,
            counts,
        };
        let input_count = available.len();
        let mut selected_total = 0u64;
        available.sort_by_key(|(_, o)| std::cmp::Reverse(o.commitment().amount));
        let mut selected = vec![];
        let mut ids = vec![];
        let mut images = BTreeSet::new();
        let mut outgoing = Zeroizing::new([0; 32]);
        OsRng
            .try_fill_bytes(outgoing.as_mut())
            .map_err(|_| EngineError::Entropy)?;
        for (id, o) in available.into_iter().take(16) {
            self.check()?;
            self.preparation
                .set_stage(PreparationStage::SelectingDecoys);
            let image = self.image(&o)?.ok_or(EngineError::ViewOnly)?;
            images.insert(
                hex::decode(image)
                    .map_err(|_| EngineError::Verification)?
                    .try_into()
                    .map_err(|_| EngineError::Verification)?,
            );
            selected_total = selected_total
                .checked_add(o.commitment().amount)
                .ok_or(EngineError::Verification)?;
            selected.push(
                OutputWithDecoys::new(
                    &mut OsRng,
                    &decoys,
                    16,
                    (self.state.next_height - 1) as usize,
                    o,
                )
                .await
                .map_err(|error| {
                    #[cfg(test)]
                    eprintln!("regtest decoy selection category: {error}");
                    match error {
                        TransactionsError::InterfaceError(error) => EngineError::node_error(error),
                        _ => EngineError::Node,
                    }
                })?,
            );
            ids.push(id);
            if requested.is_none() && selected.len() != input_count {
                continue;
            }
            // Probe the exact input/output shape without signing. Standard change
            // remains present (including zero change); no fingerprintable sweep mode.
            let atomic = if let Some(a) = requested {
                a
            } else {
                let probe = SignableTransaction::new(
                    RctType::ClsagBulletproofPlus,
                    outgoing.clone(),
                    selected.clone(),
                    vec![(recipient, 1)],
                    Change::new(self.pair.clone(), None),
                    vec![],
                    rate,
                )
                .map_err(|e| match e {
                    SendError::NotEnoughFunds { .. } => EngineError::Funds,
                    SendError::TooLargeTransaction => EngineError::InputLimit,
                    _ => EngineError::Verification,
                })?;
                let fee = probe.necessary_fee();
                if fee == 0 || fee > MAX_FEE {
                    return Err(EngineError::FeeLimit);
                }
                selected_total
                    .checked_sub(fee)
                    .filter(|a| *a > 0)
                    .ok_or(EngineError::Funds)?
            };
            let signable = match SignableTransaction::new(
                RctType::ClsagBulletproofPlus,
                outgoing.clone(),
                selected.clone(),
                vec![(recipient, atomic)],
                Change::new(self.pair.clone(), None),
                vec![],
                rate,
            ) {
                Ok(s) => s,
                Err(SendError::NotEnoughFunds { .. }) => continue,
                Err(SendError::TooLargeTransaction) => return Err(EngineError::InputLimit),
                Err(_) => return Err(EngineError::Verification),
            };
            let fee = signable.necessary_fee();
            if fee == 0 || fee > MAX_FEE {
                return Err(EngineError::FeeLimit);
            }
            if requested.is_none() && atomic.checked_add(fee) != Some(selected_total) {
                return Err(EngineError::Verification);
            }
            self.preparation.set_stage(PreparationStage::Signing);
            let intent = Eventuality::from(signable.clone());
            let tx = signable
                .sign(
                    &mut OsRng,
                    self.spend.as_ref().ok_or(EngineError::ViewOnly)?,
                )
                .map_err(|_| EngineError::Verification)?;
            let bytes = Zeroizing::new(tx.serialize());
            self.preparation.set_stage(PreparationStage::Verifying);
            let verified = verify_transaction(&bytes, intent, &selected, &images, fee)?;
            let transaction = hex::encode(verified.hash());
            let mut nonce = [0; 16];
            OsRng
                .try_fill_bytes(&mut nonce)
                .map_err(|_| EngineError::Entropy)?;
            let review_id = hex::encode(nonce);
            let payment = PendingPayment {
                transaction: transaction.clone(),
                destination: destination.to_owned(),
                amount_atomic: atomic,
                fee_atomic: fee,
                mined_height: None,
                bytes: hex::encode(&bytes),
                inputs: ids,
            };
            self.check()?;
            self.prepared = Some(Prepared {
                review_id: review_id.clone(),
                created: Instant::now(),
                payment,
                tip,
                tip_height: self.state.next_height - 1,
            });
            self.preparation.set_stage(PreparationStage::Ready);
            return Ok(PaymentReview {
                send_max: requested.is_none(),
                review_id,
                destination: destination.to_owned(),
                amount_atomic: atomic,
                fee_atomic: fee,
                transaction,
            });
        }
        Err(EngineError::Funds)
    }
    /// Advance input spent-status without rebuilding or re-signing the approved
    /// transaction. New blocks alone do not change its amount, fee or review ID.
    pub(super) async fn refresh_review_inputs(
        &mut self,
        prepared: &Prepared,
    ) -> Result<(), EngineError> {
        const MAX_REVIEW_BLOCKS: u64 = 128;
        let snapshot = u64::try_from(
            self.rpc()?
                .latest_block_number()
                .await
                .map_err(EngineError::node_error)?,
        )
        .map_err(|_| EngineError::Node)?
        .checked_add(1)
        .ok_or(EngineError::Node)?;
        if snapshot > u64::from(u32::MAX)
            || snapshot <= prepared.tip_height
            || snapshot.saturating_sub(prepared.tip_height + 1) > MAX_REVIEW_BLOCKS
        {
            return Err(EngineError::SyncRequired);
        }
        if self
            .rpc()?
            .block_hash(prepared.tip_height as usize)
            .await
            .map_err(EngineError::node_error)?
            != prepared.tip
        {
            return Err(EngineError::StaleReview);
        }
        while self.state.next_height < snapshot {
            self.check()?;
            if prepared.created.elapsed() > Duration::from_secs(300) {
                return Err(EngineError::StaleReview);
            }
            let previous = self.state.next_height;
            self.sync_window(MAX_REVIEW_BLOCKS).await?;
            if self.state.next_height <= previous
                || self.target_height.saturating_sub(prepared.tip_height + 1) > MAX_REVIEW_BLOCKS
            {
                return Err(EngineError::SyncRequired);
            }
        }
        if self
            .rpc()?
            .block_hash(prepared.tip_height as usize)
            .await
            .map_err(EngineError::node_error)?
            != prepared.tip
        {
            return Err(EngineError::StaleReview);
        }
        for key in &prepared.payment.inputs {
            let record = self
                .state
                .outputs
                .get(key)
                .ok_or(EngineError::StaleReview)?;
            let output = decode_output(record)?;
            if record.spent_height.is_some()
                || self.state.reserved(key)
                || !self.spendable(record, &output)
            {
                return Err(EngineError::StaleReview);
            }
        }
        self.check()?;
        if prepared.created.elapsed() > Duration::from_secs(300) {
            return Err(EngineError::StaleReview);
        }
        Ok(())
    }
    pub fn discard_review(&mut self) {
        self.prepared = None;
    }
}
