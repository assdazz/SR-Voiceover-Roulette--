package com.srvr.mobile;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import rikka.shizuku.ShizukuApiConstants;

interface ITouchMonitorService extends IInterface {
    String DESCRIPTOR = "com.srvr.mobile.ITouchMonitorService";
    String CALLBACK_DESCRIPTOR = "com.srvr.mobile.ITouchEventCallback";

    int ACTION_DOWN = 0;
    int ACTION_UP = 1;
    int ACTION_MOVE = 2;

    int TRANSACTION_start = IBinder.FIRST_CALL_TRANSACTION;
    int TRANSACTION_stop = IBinder.FIRST_CALL_TRANSACTION + 1;
    int TRANSACTION_getStatus = IBinder.FIRST_CALL_TRANSACTION + 2;
    int TRANSACTION_destroy = ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;

    int CALLBACK_ON_TOUCH = IBinder.FIRST_CALL_TRANSACTION;

    interface Callback extends IInterface {
        void onTouchEvent(int slot, int action, float normX, float normY) throws RemoteException;

        abstract class Stub extends Binder implements Callback {
            Stub() {
                attachInterface(this, CALLBACK_DESCRIPTOR);
            }

            static Callback asInterface(IBinder binder) {
                if (binder == null) return null;
                IInterface local = binder.queryLocalInterface(CALLBACK_DESCRIPTOR);
                if (local instanceof Callback cb) return cb;
                return new Proxy(binder);
            }

            @Override public IBinder asBinder() {
                return this;
            }

            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                }
                if (code == CALLBACK_ON_TOUCH) {
                    data.enforceInterface(CALLBACK_DESCRIPTOR);
                    int slot = data.readInt();
                    int action = data.readInt();
                    float nx = data.readFloat();
                    float ny = data.readFloat();
                    onTouchEvent(slot, action, nx, ny);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }

            private static final class Proxy implements Callback {
                private final IBinder remote;

                Proxy(IBinder remote) {
                    this.remote = remote;
                }

                @Override public IBinder asBinder() {
                    return remote;
                }

                @Override public void onTouchEvent(int slot, int action, float normX, float normY)
                        throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
                        data.writeInt(slot);
                        data.writeInt(action);
                        data.writeFloat(normX);
                        data.writeFloat(normY);
                        remote.transact(CALLBACK_ON_TOUCH, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }
            }
        }
    }

    String start(Callback callback) throws RemoteException;

    void stop() throws RemoteException;

    String getStatus() throws RemoteException;

    void destroy() throws RemoteException;

    abstract class Stub extends Binder implements ITouchMonitorService {
        Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        static ITouchMonitorService asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof ITouchMonitorService service) return service;
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
                    Callback cb = Callback.Stub.asInterface(data.readStrongBinder());
                    String result = start(cb);
                    reply.writeNoException();
                    reply.writeString(result);
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
                default -> {
                    return super.onTransact(code, data, reply, flags);
                }
            }
        }

        private static final class Proxy implements ITouchMonitorService {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override public IBinder asBinder() {
                return remote;
            }

            @Override public String start(Callback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    remote.transact(TRANSACTION_start, data, reply, 0);
                    reply.readException();
                    return reply.readString();
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
