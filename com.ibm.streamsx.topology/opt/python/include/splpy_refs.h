/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2019,2020
*/

/*
 * Internal header file supporting Python
 * for com.ibm.streamsx.topology.
 *
 * This is not part of any public api for
 * the toolkit or toolkit with decorated
 * SPL Python operators.
 *
 * Functionality related to maintaining references
 * for tuples to avoid conversion cost.
 */

#ifndef __SPL__SPLPY_REFS_H
#define __SPL__SPLPY_REFS_H

#include "splpy_general.h"

#include <SPL/Runtime/Utility/PayloadContainer.h>
#include <SPL/Runtime/Utility/Payload.h>

namespace streamsx {
  namespace topology {

class PyRefPayload: public SPL::Payload {
public:
  PyRefPayload() : ref_(0) {}

  PyRefPayload(PyObject *ref) {
    ref_ = reinterpret_cast<void *>(ref);
  }
  PyRefPayload(void *ref) {
      ref_ = ref;
  }
  

  ~PyRefPayload() {
    clearref();
   }

  PyRefPayload *clone() const {
    PyRefPayload *prp = new PyRefPayload(ref_);
    prp->ref();
    return prp;
  }

  void serialize(SPL::NativeByteBuffer & buf) const
  { }

  void deserialize(SPL::NativeByteBuffer & buf)
  { clearref();}

  void serialize(SPL::NetworkByteBuffer & buf) const
  { }

  void deserialize(SPL::NetworkByteBuffer & buf)
  { clearref(); }

  PyObject * ref() const {
      if (ref_ == 0)
          return NULL;
      PyObject *ref =  reinterpret_cast<PyObject*>(ref_);
      SplpyGIL lock;
      Py_INCREF(ref);
      return ref;
 }


private:
    void *ref_;

    void clearref() {
    if (ref_) {
        SplpyGIL lock;
        Py_DECREF(reinterpret_cast<PyObject*>(ref_));
        ref_ = NULL;
    }
    }
};

/**
 * Returns a Python object reference owned by the caller (ref count bumped)
 * that was previously saved by addTupleRef.
 * Returns NULL if no object was added to the tuple.
 */
inline PyObject *getTupleRef(SPL::Tuple const &tuple) {
    const SPL::PayloadContainer *pc = tuple.getPayloadContainer();
    if (pc == NULL)
         return NULL;

    const SPL::Payload *pl = pc->find("pyref");
    if (pl == NULL)
        return NULL;

    const PyRefPayload *prp = reinterpret_cast<const PyRefPayload *>(pl);

    return prp->ref();
}

/**
 * Adds a Python object reference to the tuple as a payload.
 *
 * The associated object for the SPL tuple can be obtained
 * using getTupleRef, even if the SPL tuple was copied by
 * the SPL runtime.
 *
 * This function steals the reference to ref.
 */
inline void addTupleRef(SPL::Tuple *otuple, PyObject *ref) {
    SPL::PayloadContainer *pc = otuple->getPayloadContainer();
    if (pc == NULL) {
       pc = new SPL::PayloadContainer();
       otuple->setPayloadContainer(pc);
    }
    PyRefPayload *pyref = new streamsx::topology::PyRefPayload(ref);
    pc->add("pyref", *pyref);
}
}}

#endif /* __SPL__SPLPY_REFS_H */
