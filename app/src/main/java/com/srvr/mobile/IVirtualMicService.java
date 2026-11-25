package com.srvr.mobile;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import rikka.shizuku.ShizukuApiConstants;

interface IVirtualMicService extends IInterface {
    String DESCRIPTOR = "com.srvr.mobile.IVirtualMicService";

    int MODE_ALWAYS_SFX = 0;
    int MODE_ON_DEMAND = 1;
    int MODE_MIX_REAL_MIC = 2;

    int TRANSACTION_start = IBinder.FIRST_CALL_TRANSACTION;
    int TRANSACTION_updateConfig = IBinder.FIRST_CALL_TRANSACTION + 1;
    int TRANSACTION_setInjectionActive = IBinder.FIRST_CALL_TRANSACTION + 2;
    int TRANSACTION_stop = IBinder.FIRST_CALL_TRANSACTION + 3;
    int TRANSACTION_getStatus = IBinder.FIRST_CALL_TRANSACTION + 4;
    int TRANSACTION_shutdownShizuku = IBinder.FIRST_CALL_TRANSACTION + 5;
    int TRANSACTION_destroy = ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;

    String start(int appUid, int mode, float realMicGain, float sfxGain,
            boolean duckMicOnSfx, ParcelFileDescriptor pcmReadPipe) throws RemoteException;

    void updateConfig(int mode, float realMicGain, float sfxGain,
            boolean duckMicOnSfx) throws RemoteException;

    void setInjectionActive(boolean active) throws RemoteException;

    void stop() throws RemoteException;

    String getStatus() throws RemoteException;

    String shutdownShizuku(boolean disableWirelessAdb) throws RemoteException;

    void destroy() throws RemoteException;

    abstract class Stub extends Binder implements IVirtualMicService {
        Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        static IVirtualMicService asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IVirtualMicService service) return service;
            return new Proxy(binder);
        }

        @Override public IBinder asBinder() {
            return this;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_destroy) {
                data.enforceInterface(DESCRIPTOR);
                destroy();
                reply.writeNoException();
                return true;
            }
            switch (code) {
                case TRANSACTION_start -> {
                    data.enforceInterface(DESCRIPTOR);
                    int appUid = data.readInt();
                    int mode = data.readInt();
                    float realMicGain = data.readFloat();
                    float sfxGain = data.readFloat();
                    boolean duckMicOnSfx = data.readInt() != 0;
                    ParcelFileDescriptor pipe = data.readInt() != 0
                            ? ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
                    String result = start(appUid, mode, realMicGain, sfxGain, duckMicOnSfx, pipe);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_updateConfig -> {
                    data.enforceInterface(DESCRIPTOR);
                    int mode = data.readInt();
                    float realMicGain = data.readFloat();
                    float sfxGain = data.readFloat();
                    boolean duckMicOnSfx = data.readInt() != 0;
                    updateConfig(mode, realMicGain, sfxGain, duckMicOnSfx);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_setInjectionActive -> {
                    data.enforceInterface(DESCRIPTOR);
                    boolean active = data.readInt() != 0;
                    setInjectionActive(active);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_stop -> {
                    data.enforceInterface(DESCRIPTOR);
                    stop();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getStatus -> {
                    data.enforceInterface(DESCRIPTOR);
                    String status = getStatus();
                    reply.writeNoException();
                    reply.writeString(status);
                    return true;
                }
                case TRANSACTION_shutdownShizuku -> {
                    data.enforceInterface(DESCRIPTOR);
                    boolean disableWirelessAdb = data.readInt() != 0;
                    String result = shutdownShizuku(disableWirelessAdb);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                default -> {
                    return super.onTransact(code, data, reply, flags);
                }
            }
        }

        private static final class Proxy implements IVirtualMicService {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override public IBinder asBinder() {
                return remote;
            }

            @Override public String start(int appUid, int mode, float realMicGain, float sfxGain,
                    boolean duckMicOnSfx, ParcelFileDescriptor pcmReadPipe) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(appUid);
                    data.writeInt(mode);
                    data.writeFloat(realMicGain);
                    data.writeFloat(sfxGain);
                    data.writeInt(duckMicOnSfx ? 1 : 0);
                    if (pcmReadPipe != null) {
                        data.writeInt(1);
                        pcmReadPipe.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    remote.transact(TRANSACTION_start, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public void updateConfig(int mode, float realMicGain, float sfxGain,
                    boolean duckMicOnSfx) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(mode);
                    data.writeFloat(realMicGain);
                    data.writeFloat(sfxGain);
                    data.writeInt(duckMicOnSfx ? 1 : 0);
                    remote.transact(TRANSACTION_updateConfig, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public void setInjectionActive(boolean active) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(active ? 1 : 0);
                    remote.transact(TRANSACTION_setInjectionActive, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public void stop() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_stop, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public String getStatus() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_getStatus, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public String shutdownShizuku(boolean disableWirelessAdb) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(disableWirelessAdb ? 1 : 0);
                    remote.transact(TRANSACTION_shutdownShizuku, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_destroy, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
