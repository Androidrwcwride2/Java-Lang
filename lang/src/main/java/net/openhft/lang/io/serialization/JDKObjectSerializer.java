/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.lang.io.serialization;

import net.openhft.lang.io.Bytes;
import net.openhft.lang.model.constraints.NotNull;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public enum JDKObjectSerializer implements ObjectSerializer {
    INSTANCE;

    @Override
    public void writeSerializable(Bytes bytes, Object object, Class expectedClass) throws IOException {
        ObjectOutputStream oos= new ObjectOutputStream(bytes.outputStream());
        oos.writeObject(object);
        oos.close();
    }

    @Override
    public <T> T readSerializable(@NotNull Bytes bytes, Class<T> expectedClass, T object) throws IOException, ClassNotFoundException {
        ObjectInputStream ois = new ObjectInputStream(bytes.inputStream());
        T obj = (T) ois.readObject();
        ois.close();
        return obj;
    }
}
