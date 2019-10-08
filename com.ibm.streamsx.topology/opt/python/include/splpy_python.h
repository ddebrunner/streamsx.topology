/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2019
*/

/*
 * Internal header file supporting Python
 * for com.ibm.streamsx.topology.
 *
 * This is not part of any public api for
 * the toolkit or toolkit with decorated
 * SPL Python operators.
 *
 * Consistent inclusion of Python.h
 *
 */

#ifndef __SPL__SPLPY_PYTHON_H
#define __SPL__SPLPY_PYTHON_H

#define Py_LIMITED_API ((3 << 24) | (5 << 16))

#define PyTuple_GET_SIZE(t) PyTuple_Size(t)
#define PyTuple_GET_ITEM(t, pos) PyTuple_GetItem((t), (pos))
#define PyTuple_SET_ITEM(t, pos, o) PyTuple_SetItem((t), (pos), (o))

#include "Python.h"

#endif

