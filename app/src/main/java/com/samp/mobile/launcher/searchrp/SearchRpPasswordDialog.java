package com.samp.mobile.launcher.searchrp;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.samp.mobile.R;

/**
 * GTA San Andreas Noir Server Password Gate modal for Search Roleplay Mobile.
 */
public final class SearchRpPasswordDialog {

    public interface Callback {
        void onSubmit(String password, boolean remember);
        void onCancel();
    }

    public static void show(
            Context ctx,
            SrpConfig cfg,
            SrpUdpQuery.ServerInfo srvInfo,
            String lang,
            Callback callback) {
        Dialog dlg = new Dialog(ctx, R.style.Theme_SearchRP_Dialog);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_srp_password, null);
        dlg.setContentView(root);
        dlg.setCancelable(true);

        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int widthPx = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f);
            dlg.getWindow().setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        TextView tvEyebrow = root.findViewById(R.id.pw_dlg_eyebrow);
        TextView tvTitle = root.findViewById(R.id.pw_dlg_title);
        TextView tvHost = root.findViewById(R.id.pw_dlg_hostname);
        TextView tvMeta = root.findViewById(R.id.pw_dlg_meta);
        TextView tvLockedPill = root.findViewById(R.id.pw_dlg_locked_pill);
        TextView tvFieldLbl = root.findViewById(R.id.pw_dlg_field_lbl);
        TextView tvFieldHint = root.findViewById(R.id.pw_dlg_field_hint);
        LinearLayout inputBox = root.findViewById(R.id.pw_dlg_input_box);
        ImageView keyIcon = root.findViewById(R.id.pw_dlg_key_icon);
        EditText editPw = root.findViewById(R.id.pw_dlg_edit);
        ImageButton eyeBtn = root.findViewById(R.id.pw_dlg_eye_btn);
        TextView tvError = root.findViewById(R.id.pw_dlg_error);
        CheckBox chkRemember = root.findViewById(R.id.pw_dlg_remember);
        TextView tvClear = root.findViewById(R.id.pw_dlg_clear);
        TextView btnCancel = root.findViewById(R.id.pw_dlg_btn_cancel);
        View btnJoin = root.findViewById(R.id.pw_dlg_btn_join);
        TextView btnJoinTxt = root.findViewById(R.id.pw_dlg_btn_join_txt);

        // Apply EN / ID strings
        tvEyebrow.setText(SrpI18n.tr(lang, "pw_eyebrow"));
        tvTitle.setText(SrpI18n.tr(lang, "pw_title"));
        tvLockedPill.setText(SrpI18n.tr(lang, "status_locked"));
        tvFieldLbl.setText(SrpI18n.tr(lang, "pw_field_lbl"));
        tvFieldHint.setText(SrpI18n.tr(lang, "pw_field_hint"));
        editPw.setHint(SrpI18n.tr(lang, "pw_ph"));
        chkRemember.setText(SrpI18n.tr(lang, "pw_remember"));
        tvClear.setText(SrpI18n.tr(lang, "pw_clear"));
        btnCancel.setText(SrpI18n.tr(lang, "btn_cancel"));
        btnJoinTxt.setText(SrpI18n.tr(lang, "pw_btn_join"));

        String host = (srvInfo != null && srvInfo.hostname != null && !srvInfo.hostname.isEmpty())
                ? srvInfo.hostname : "SEARCH ROLEPLAY";
        tvHost.setText(host);
        String meta = cfg.getServerIp() + ":" + cfg.getServerPort();
        if (srvInfo != null && srvInfo.online && srvInfo.maxPlayers > 0) {
            meta += "  •  " + srvInfo.players + "/" + srvInfo.maxPlayers + " Online";
        }
        tvMeta.setText(meta);

        String savedPw = cfg.getSavedServerPassword();
        chkRemember.setChecked(cfg.isRememberServerPassword());
        if (savedPw != null && !savedPw.isEmpty()) {
            editPw.setText(savedPw);
            editPw.setSelection(savedPw.length());
            tvClear.setVisibility(View.VISIBLE);
        }

        final int colGold = ContextCompat.getColor(ctx, R.color.srp_gold);
        final int colMuted = ContextCompat.getColor(ctx, R.color.srp_text_muted);
        final int colRed = ContextCompat.getColor(ctx, R.color.srp_crimson_hover);
        final boolean[] visible = new boolean[]{false};
        final boolean[] submitted = new boolean[]{false};

        editPw.setOnFocusChangeListener((v, hasFocus) -> {
            if (tvError.getText().length() == 0) {
                inputBox.setBackgroundResource(hasFocus ? R.drawable.srp_input_gold_bg : R.drawable.srp_input_bg);
                keyIcon.setColorFilter(hasFocus ? colGold : colMuted);
            }
        });

        editPw.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (tvError.getText().length() > 0 && s.length() > 0) {
                    tvError.setText("");
                    inputBox.setBackgroundResource(R.drawable.srp_input_gold_bg);
                    keyIcon.setColorFilter(colGold);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        eyeBtn.setOnClickListener(v -> {
            visible[0] = !visible[0];
            int sel = editPw.getSelectionEnd();
            editPw.setInputType(visible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            editPw.setTypeface(tvHost.getTypeface());
            if (sel >= 0) editPw.setSelection(Math.min(sel, editPw.getText().length()));
            eyeBtn.setImageResource(visible[0] ? R.drawable.ic_srp_eye_off : R.drawable.ic_srp_eye);
            eyeBtn.setColorFilter(visible[0] ? colGold : colMuted);
        });

        tvClear.setOnClickListener(v -> {
            editPw.setText("");
            chkRemember.setChecked(false);
            tvClear.setVisibility(View.GONE);
            tvError.setText("");
            inputBox.setBackgroundResource(R.drawable.srp_input_gold_bg);
            keyIcon.setColorFilter(colGold);
            editPw.requestFocus();
        });

        Runnable trySubmit = () -> {
            String pw = editPw.getText().toString();
            if (pw.trim().isEmpty()) {
                tvError.setText(SrpI18n.tr(lang, "pw_err_empty"));
                inputBox.setBackgroundResource(R.drawable.srp_input_error_bg);
                keyIcon.setColorFilter(colRed);
                ObjectAnimator.ofFloat(inputBox, "translationX", 0f, 16f, -16f, 12f, -12f, 6f, -6f, 0f)
                        .setDuration(300)
                        .start();
                editPw.requestFocus();
                return;
            }
            submitted[0] = true;
            dlg.dismiss();
            if (callback != null) {
                callback.onSubmit(pw, chkRemember.isChecked());
            }
        };

        btnJoin.setOnClickListener(v -> trySubmit.run());
        editPw.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                trySubmit.run();
                return true;
            }
            return false;
        });

        btnCancel.setOnClickListener(v -> dlg.cancel());
        dlg.setOnCancelListener(d -> {
            if (!submitted[0] && callback != null) {
                callback.onCancel();
            }
        });

        dlg.show();
        editPw.requestFocus();
    }
}
