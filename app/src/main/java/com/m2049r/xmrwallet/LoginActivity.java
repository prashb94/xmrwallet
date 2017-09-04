/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.m2049r.xmrwallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;

import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.service.WalletService;
import com.m2049r.xmrwallet.util.Helper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public class LoginActivity extends AppCompatActivity
        implements LoginFragment.Listener, GenerateFragment.Listener, GenerateReviewFragment.Listener {
    static final String TAG = "LoginActivity";
    private static final String GENERATE_STACK = "gen";

    static final int DAEMON_TIMEOUT = 500; // deamon must respond in 500ms

    Toolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login_activity);
        if (savedInstanceState != null) {
            return;
        }

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (Helper.getWritePermission(this)) {
            startLoginFragment();
        } else {
            Log.i(TAG, "Waiting for permissions");
        }
    }

    boolean checkServiceRunning() {
        if (WalletService.Running) {
            Toast.makeText(this, getString(R.string.service_busy), Toast.LENGTH_SHORT).show();
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onWalletSelected(final String walletName) {
        if (checkServiceRunning()) return;
        Log.d(TAG, "selected wallet is ." + walletName + ".");
        // now it's getting real, check if wallet exists
        File walletFile = Helper.getWalletFile(this, walletName);
        if (WalletManager.getInstance().walletExists(walletFile)) {
            promptPassword(walletName, new PasswordAction() {
                @Override
                public void action(String walletName, String password) {
                    startWallet(walletName, password);
                }
            });
        } else { // this cannot really happen as we prefilter choices
            Toast.makeText(this, getString(R.string.bad_wallet), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onWalletDetails(final String walletName) {
        Log.d(TAG, "details for wallet ." + walletName + ".");
        if (checkServiceRunning()) return;
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        final File walletFile = Helper.getWalletFile(LoginActivity.this, walletName);
                        if (WalletManager.getInstance().walletExists(walletFile)) {
                            promptPassword(walletName, new PasswordAction() {
                                @Override
                                public void action(String walletName, String password) {
                                    startDetails(walletFile, password, GenerateReviewFragment.VIEW_TYPE_DETAILS);
                                }
                            });
                        } else { // this cannot really happen as we prefilter choices
                            Log.e(TAG, "Wallet missing: " + walletName);
                            Toast.makeText(LoginActivity.this, getString(R.string.bad_wallet), Toast.LENGTH_SHORT).show();
                        }
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        // do nothing
                        break;
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(getString(R.string.details_alert_message))
                .setPositiveButton(getString(R.string.details_alert_yes), dialogClickListener)
                .setNegativeButton(getString(R.string.details_alert_no), dialogClickListener)
                .show();
    }

    @Override
    public void onWalletReceive(String walletName) {
        Log.d(TAG, "receive for wallet ." + walletName + ".");
        if (checkServiceRunning()) return;
        final File walletFile = Helper.getWalletFile(this, walletName);
        if (WalletManager.getInstance().walletExists(walletFile)) {
            promptPassword(walletName, new PasswordAction() {
                @Override
                public void action(String walletName, String password) {
                    startReceive(walletFile, password);
                }
            });
        } else { // this cannot really happen as we prefilter choices
            Toast.makeText(this, getString(R.string.bad_wallet), Toast.LENGTH_SHORT).show();
        }
    }

    private class AsyncRename extends AsyncTask<String, Void, Boolean> {
        ProgressDialog progressDialog = new MyProgressDialog(LoginActivity.this, R.string.rename_progress);

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog.show();
            LoginActivity.this.asyncWaitTask = this;
        }

        @Override
        protected Boolean doInBackground(String... params) {
            if (params.length != 2) return false;
            File walletFile = Helper.getWalletFile(LoginActivity.this, params[0]);
            String newName = params[1];
            return renameWallet(walletFile, newName);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            progressDialog.dismiss();
            if (result) {
                reloadWalletList();
            } else {
                Toast.makeText(LoginActivity.this, getString(R.string.rename_failed), Toast.LENGTH_LONG).show();
            }
            LoginActivity.this.asyncWaitTask = null;
        }
    }

    // copy + delete seems safer than rename bevause we call rollback easily
    boolean renameWallet(File walletFile, String newName) {
        if (copyWallet(walletFile, new File(walletFile.getParentFile(), newName), false)) {
            deleteWallet(walletFile);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onWalletRename(final String walletName) {
        Log.d(TAG, "rename for wallet ." + walletName + ".");
        if (checkServiceRunning()) return;
        LayoutInflater li = LayoutInflater.from(this);
        View promptsView = li.inflate(R.layout.prompt_rename, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setView(promptsView);

        final EditText etRename = (EditText) promptsView.findViewById(R.id.etRename);
        final TextView tvRenameLabel = (TextView) promptsView.findViewById(R.id.tvRenameLabel);

        tvRenameLabel.setText(getString(R.string.prompt_rename) + " " + walletName);

        // set dialog message
        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Helper.hideKeyboardAlways(LoginActivity.this);
                                String newName = etRename.getText().toString();
                                new AsyncRename().execute(walletName, newName);
                            }
                        })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Helper.hideKeyboardAlways(LoginActivity.this);
                                dialog.cancel();
                            }
                        });

        final AlertDialog dialog = alertDialogBuilder.create();
        Helper.showKeyboard(dialog);

        // accept keyboard "ok"
        etRename.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) || (actionId == EditorInfo.IME_ACTION_DONE)) {
                    Helper.hideKeyboardAlways(LoginActivity.this);
                    String newName = etRename.getText().toString();
                    dialog.cancel();
                    new AsyncRename().execute(walletName, newName);
                    return false;
                }
                return false;
            }
        });

        dialog.show();
    }


    private class AsyncBackup extends AsyncTask<String, Void, Boolean> {
        ProgressDialog progressDialog = new MyProgressDialog(LoginActivity.this, R.string.backup_progress);

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog.show();
            LoginActivity.this.asyncWaitTask = this;
        }

        @Override
        protected Boolean doInBackground(String... params) {
            if (params.length != 1) return false;
            return backupWallet(params[0]);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            progressDialog.dismiss();
            if (!result) {
                Toast.makeText(LoginActivity.this, getString(R.string.backup_failed), Toast.LENGTH_LONG).show();
            }
            LoginActivity.this.asyncWaitTask = null;
        }
    }

    private boolean backupWallet(String walletName) {
        File backupFolder = new File(getStorageRoot(), ".backups");
        if (!backupFolder.exists()) {
            if (!backupFolder.mkdir()) {
                Log.e(TAG, "Cannot create backup dir " + backupFolder.getAbsolutePath());
                return false;
            }
        }
        File walletFile = Helper.getWalletFile(LoginActivity.this, walletName);
        File backupFile = new File(backupFolder, walletName);
        Log.d(TAG, "backup " + walletFile.getAbsolutePath() + " to " + backupFile.getAbsolutePath());
        // TODO probably better to copy to a new file and then rename
        // then if something fails we have the old backup at least
        // or just create a new backup every time and keep n old backups
        return copyWallet(walletFile, backupFile, true);
    }

    @Override
    public void onWalletBackup(String walletName) {
        Log.d(TAG, "backup for wallet ." + walletName + ".");
        new AsyncBackup().execute(walletName);
    }

    private class AsyncArchive extends AsyncTask<String, Void, Boolean> {
        ProgressDialog progressDialog = new MyProgressDialog(LoginActivity.this, R.string.archive_progress);

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog.show();
            LoginActivity.this.asyncWaitTask = this;
        }

        @Override
        protected Boolean doInBackground(String... params) {
            if (params.length != 1) return false;
            String walletName = params[0];
            if (backupWallet(walletName) && deleteWallet(Helper.getWalletFile(LoginActivity.this, walletName))) {
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            progressDialog.dismiss();
            if (result) {
                reloadWalletList();
            } else {
                Toast.makeText(LoginActivity.this, getString(R.string.archive_failed), Toast.LENGTH_LONG).show();
            }
            LoginActivity.this.asyncWaitTask = null;
        }
    }

    @Override
    public void onWalletArchive(final String walletName) {
        Log.d(TAG, "archive for wallet ." + walletName + ".");
        if (checkServiceRunning()) return;
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        new AsyncArchive().execute(walletName);
                        break;
                    case DialogInterface.BUTTON_NEGATIVE:
                        // do nothing
                        break;
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(getString(R.string.archive_alert_message))
                .setTitle(walletName)
                .setPositiveButton(getString(R.string.archive_alert_yes), dialogClickListener)
                .setNegativeButton(getString(R.string.archive_alert_no), dialogClickListener)
                .show();
    }

    void reloadWalletList() {
        try {
            LoginFragment loginFragment = (LoginFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            loginFragment.loadList();
        } catch (ClassCastException ex) {
        }
    }

    @Override
    public void onAddWallet() {
        if (checkServiceRunning()) return;
        startGenerateFragment();
    }

    AlertDialog passwordDialog = null; // for preventing multiple clicks in wallet list

    void promptPassword(final String wallet, final PasswordAction action) {
        if (passwordDialog != null) return; // we are already asking for password
        Context context = LoginActivity.this;
        LayoutInflater li = LayoutInflater.from(context);
        View promptsView = li.inflate(R.layout.prompt_password, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setView(promptsView);

        final EditText etPassword = (EditText) promptsView.findViewById(R.id.etPassword);
        final TextView tvPasswordLabel = (TextView) promptsView.findViewById(R.id.tvPasswordLabel);

        tvPasswordLabel.setText(LoginActivity.this.getString(R.string.prompt_password) + " " + wallet);

        // set dialog message
        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Helper.hideKeyboardAlways(LoginActivity.this);
                                String pass = etPassword.getText().toString();
                                processPasswordEntry(wallet, pass, action);
                                passwordDialog = null;
                            }
                        })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Helper.hideKeyboardAlways(LoginActivity.this);
                                dialog.cancel();
                                passwordDialog = null;
                            }
                        });

        passwordDialog = alertDialogBuilder.create();
        Helper.showKeyboard(passwordDialog);

        // accept keyboard "ok"
        etPassword.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) || (actionId == EditorInfo.IME_ACTION_DONE)) {
                    Helper.hideKeyboardAlways(LoginActivity.this);
                    String pass = etPassword.getText().toString();
                    passwordDialog.cancel();
                    processPasswordEntry(wallet, pass, action);
                    passwordDialog = null;
                    return false;
                }
                return false;
            }
        });

        passwordDialog.show();
    }

    private boolean checkWalletPassword(String walletName, String password) {
        String walletPath = new File(Helper.getStorageRoot(getApplicationContext()),
                walletName + ".keys").getAbsolutePath();
        // only test view key
        return WalletManager.getInstance().verifyWalletPassword(walletPath, password, true);
    }

    interface PasswordAction {
        void action(String walletName, String password);
    }

    private void processPasswordEntry(String walletName, String pass, PasswordAction action) {
        if (checkWalletPassword(walletName, pass)) {
            action.action(walletName, pass);
        } else {
            Toast.makeText(this, getString(R.string.bad_password), Toast.LENGTH_SHORT).show();
        }
    }

    ////////////////////////////////////////
    // LoginFragment.Listener
    ////////////////////////////////////////
    @Override
    public SharedPreferences getPrefs() {
        return getPreferences(Context.MODE_PRIVATE);
    }

    @Override
    public File getStorageRoot() {
        return Helper.getStorageRoot(getApplicationContext());
    }

    @Override
    public void setTitle(String title) {
        toolbar.setTitle(title);
    }

    @Override
    public void setSubtitle(String subtitle) {
        toolbar.setSubtitle(subtitle);
    }

    @Override
    public void setTestNet(boolean testnet) {
        if (testnet) {
            toolbar.setBackgroundResource(R.color.colorPrimaryDark);
        } else {
            toolbar.setBackgroundResource(R.color.moneroOrange);
        }
    }
    ////////////////////////////////////////
    ////////////////////////////////////////

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
    }

    AsyncTask asyncWaitTask = null;

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");
        if (WalletService.Running && (asyncWaitTask == null)) {
            Log.d(TAG, "new process dialog");
            new AsyncWaitForService().execute();
        }
    }


    private class MyProgressDialog extends ProgressDialog {
        Activity activity;

        public MyProgressDialog(Activity activity, int msgId) {
            super(activity);
            this.activity = activity;
            setCancelable(false);
            setMessage(activity.getString(msgId));
        }

        @Override
        public void onBackPressed() {
            //activity.finish();
        }
    }


    private class AsyncWaitForService extends AsyncTask<Void, Void, Void> {
        ProgressDialog progressDialog = new MyProgressDialog(LoginActivity.this, R.string.service_progress);

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog.show();
            LoginActivity.this.asyncWaitTask = this;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                while (WalletService.Running & !isCancelled()) {
                    Thread.sleep(250);
                }
            } catch (InterruptedException ex) {
                // oh well ...
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            progressDialog.dismiss();
            LoginActivity.this.asyncWaitTask = null;
        }
    }


    void startWallet(String walletName, String walletPassword) {
        Log.d(TAG, "startWallet()");
        Intent intent = new Intent(getApplicationContext(), WalletActivity.class);
        intent.putExtra(WalletActivity.REQUEST_ID, walletName);
        intent.putExtra(WalletActivity.REQUEST_PW, walletPassword);
        startActivity(intent);
    }

    void startDetails(File walletFile, String password, String type) {
        Log.d(TAG, "startDetails()");
        Bundle b = new Bundle();
        b.putString("path", walletFile.getAbsolutePath());
        b.putString("password", password);
        b.putString("type", type);
        startReviewFragment(b);
    }

    void startReceive(File walletFile, String password) {
        Log.d(TAG, "startReceive()");
        Bundle b = new Bundle();
        b.putString("path", walletFile.getAbsolutePath());
        b.putString("password", password);
        startReceiveFragment(b);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult()");
        switch (requestCode) {
            case Helper.PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLoginFragment();
                } else {
                    String msg = getString(R.string.message_strorage_not_permitted);
                    Log.e(TAG, msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    //throw new IllegalStateException(msg);
                }
                break;
            default:
        }
    }

    void startLoginFragment() {
        Fragment fragment = new LoginFragment();
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, fragment).commit();
        Log.d(TAG, "LoginFragment added");
    }

    void startGenerateFragment() {
        replaceFragment(new GenerateFragment(), GENERATE_STACK, null);
        Log.d(TAG, "GenerateFragment placed");
    }

    void startReviewFragment(Bundle extras) {
        replaceFragment(new GenerateReviewFragment(), null, extras);
        Log.d(TAG, "GenerateReviewFragment placed");
    }

    void startReceiveFragment(Bundle extras) {
        replaceFragment(new ReceiveFragment(), null, extras);
        Log.d(TAG, "ReceiveFragment placed");
    }

    void replaceFragment(Fragment newFragment, String stackName, Bundle extras) {
        if (extras != null) {
            newFragment.setArguments(extras);
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, newFragment);
        transaction.addToBackStack(stackName);
        transaction.commit();
    }

    void popFragmentStack(String name) {
        getSupportFragmentManager().popBackStack(name, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    //////////////////////////////////////////
    // GenerateFragment.Listener
    //////////////////////////////////////////
    static final String MNEMONIC_LANGUAGE = "English"; // see mnemonics/electrum-words.cpp for more

    // TODO make this an AsyncTask?
    public void createWallet(final String name, final String password, final WalletCreator walletCreator) {
        final GenerateFragment genFragment = (GenerateFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        File newWalletFolder = new File(getStorageRoot(), ".new");
        if (!newWalletFolder.exists()) {
            if (!newWalletFolder.mkdir()) {
                Log.e(TAG, "Cannot create new wallet dir " + newWalletFolder.getAbsolutePath());
                genFragment.walletGenerateError();
                return;
            }
        }
        if (!newWalletFolder.isDirectory()) {
            Log.e(TAG, "New wallet dir " + newWalletFolder.getAbsolutePath() + "is not a directory");
            genFragment.walletGenerateError();
            return;
        }
        File cacheFile = new File(newWalletFolder, name);
        cacheFile.delete();
        File keysFile = new File(newWalletFolder, name + ".keys");
        keysFile.delete();
        File addressFile = new File(newWalletFolder, name + ".address.txt");
        addressFile.delete();

        if (cacheFile.exists() || keysFile.exists() || addressFile.exists()) {
            Log.e(TAG, "Cannot remove all old wallet files: " + cacheFile.getAbsolutePath());
            genFragment.walletGenerateError();
            return;
        }

        File newWalletFile = new File(newWalletFolder, name);
        boolean success = walletCreator.createWallet(newWalletFile, password);
        if (success) {
            startDetails(newWalletFile, password, GenerateReviewFragment.VIEW_TYPE_ACCEPT);
        } else {
            Toast.makeText(LoginActivity.this,
                    getString(R.string.generate_wallet_create_failed), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Could not create new wallet in " + newWalletFile.getAbsolutePath());
            genFragment.walletGenerateError();
        }
    }

    interface WalletCreator {
        boolean createWallet(File aFile, String password);
    }

    @Override
    public void onGenerate(String name, String password) {
        createWallet(name, password,
                new WalletCreator() {
                    public boolean createWallet(File aFile, String password) {
                        Wallet newWallet = WalletManager.getInstance()
                                .createWallet(aFile, password, MNEMONIC_LANGUAGE);
                        boolean success = (newWallet.getStatus() == Wallet.Status.Status_Ok);
                        if (!success) Log.e(TAG, newWallet.getErrorString());
                        newWallet.close();
                        return success;
                    }
                });
    }

    @Override
    public void onGenerate(String name, String password, final String seed, final long restoreHeight) {
        createWallet(name, password,
                new WalletCreator() {
                    public boolean createWallet(File aFile, String password) {
                        Wallet newWallet = WalletManager.getInstance().recoveryWallet(aFile, seed, restoreHeight);
                        boolean success = (newWallet.getStatus() == Wallet.Status.Status_Ok);
                        if (success) {
                            newWallet.setPassword(password);
                            success = success && newWallet.store();
                        } else {
                            Log.e(TAG, newWallet.getErrorString());
                        }
                        newWallet.close();
                        return success;
                    }
                });
    }

    @Override
    public void onGenerate(String name, String password,
                           final String address, final String viewKey, final String spendKey, final long restoreHeight) {
        createWallet(name, password,
                new WalletCreator() {
                    public boolean createWallet(File aFile, String password) {
                        Wallet newWallet = WalletManager.getInstance()
                                .createWalletFromKeys(aFile, MNEMONIC_LANGUAGE, restoreHeight,
                                        address, viewKey, spendKey);
                        boolean success = (newWallet.getStatus() == Wallet.Status.Status_Ok);
                        if (success) {
                            newWallet.setPassword(password);
                            success = success && newWallet.store();
                        } else {
                            Log.e(TAG, newWallet.getErrorString());
                        }
                        newWallet.close();
                        return success;
                    }
                });
    }


    @Override
    public void onAccept(final String name, final String password) {
        final File newWalletFile = new File(new File(getStorageRoot(), ".new"), name);
        final File walletFolder = getStorageRoot();
        final File walletFile = new File(walletFolder, name);
        final boolean rc = copyWallet(newWalletFile, walletFile, false)
                &&
                (testWallet(walletFile.getAbsolutePath(), password) == Wallet.Status.Status_Ok);
        if (rc) {
            popFragmentStack(GENERATE_STACK);
            Toast.makeText(LoginActivity.this,
                    getString(R.string.generate_wallet_created), Toast.LENGTH_SHORT).show();
        } else {
            Log.e(TAG, "Wallet store failed to " + walletFile.getAbsolutePath());
            Toast.makeText(LoginActivity.this,
                    getString(R.string.generate_wallet_create_failed_2), Toast.LENGTH_LONG).show();
        }
    }

    Wallet.Status testWallet(String path, String password) {
        Log.d(TAG, "testing wallet " + path);
        Wallet aWallet = WalletManager.getInstance().openWallet(path, password);
        if (aWallet == null) return Wallet.Status.Status_Error; // does this ever happen?
        Wallet.Status status = aWallet.getStatus();
        Log.d(TAG, "wallet tested " + aWallet.getStatus());
        aWallet.close();
        return status;
    }

    boolean walletExists(File walletFile, boolean any) {
        File dir = walletFile.getParentFile();
        String name = walletFile.getName();
        if (any) {
            return new File(dir, name).exists()
                    || new File(dir, name + ".keys").exists()
                    || new File(dir, name + ".address.txt").exists();
        } else {
            return new File(dir, name).exists()
                    && new File(dir, name + ".keys").exists()
                    && new File(dir, name + ".address.txt").exists();
        }
    }

    boolean copyWallet(File srcWallet, File dstWallet, boolean overwrite) {
        //Log.d(TAG, "src=" + srcWallet.exists() + " dst=" + dstWallet.exists());
        if (walletExists(dstWallet, true) && !overwrite) return false;
        if (!walletExists(srcWallet, false)) return false;

        boolean success = false;
        File srcDir = srcWallet.getParentFile();
        String srcName = srcWallet.getName();
        File dstDir = dstWallet.getParentFile();
        String dstName = dstWallet.getName();
        try {
            copyFile(new File(srcDir, srcName), new File(dstDir, dstName));
            copyFile(new File(srcDir, srcName + ".keys"), new File(dstDir, dstName + ".keys"));
            copyFile(new File(srcDir, srcName + ".address.txt"), new File(dstDir, dstName + ".address.txt"));
            success = true;
        } catch (IOException ex) {
            Log.e(TAG, "wallet copy failed: " + ex.getMessage());
            // try to rollback
            deleteWallet(dstWallet);
        }
        return success;
    }

    // do our best to delete as much as possible of the wallet files
    boolean deleteWallet(File walletFile) {
        Log.d(TAG, "deleteWallet " + walletFile.getAbsolutePath());
        if (!walletFile.isFile()) return false;
        File dir = walletFile.getParentFile();
        String name = walletFile.getName();
        boolean success = new File(dir, name).delete();
        success = new File(dir, name + ".keys").delete() && success;
        success = new File(dir, name + ".address.txt").delete() && success;
        return success;
    }

    void copyFile(File src, File dst) throws IOException {
        FileChannel inChannel = new FileInputStream(src).getChannel();
        FileChannel outChannel = new FileOutputStream(dst).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
            if (inChannel != null)
                inChannel.close();
            if (outChannel != null)
                outChannel.close();
        }
    }
}
