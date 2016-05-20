#include "Python.h"
#include <string>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdio.h>
#include <memory>
#include <dlfcn.h>

#include <SPL/Runtime/Operator/Operator.h>
#include <SPL/Runtime/Operator/OperatorContext.h>
#include <SPL/Runtime/ProcessingElement/PE.h>

/**
 * Functionality for executing Python within IBM Streams.
 */

    
namespace streamsx {
  namespace topology {

    class PyGILLock {
      public:
        PyGILLock() {
          gstate_ = PyGILState_Ensure();
        }
        ~PyGILLock() {
          PyGILState_Release(gstate_);
        }
        
      private:
        PyGILState_STATE gstate_;
    };

    /*
    ** Conversion of Python objects to SPL attributes.
    */

    template <class T>
    inline void pyAttributeFromPyObject(T & attr, PyObject *);
    
    inline void pyAttributeFromPyObject(SPL::blob & attr, PyObject * value) {
      // construct spl blob from pickled value
      long int size = PyBytes_Size(value);
      char * bytes = PyBytes_AsString(value);          
      attr.setData((const unsigned char *)bytes, size);
    }

    /*
    ** Conversion of SPL attributes to Python objects
    */

    template <class T>
    inline PyObject * pyAttributeToPyObject(T & attr);

    /**
     * Convert a SPL blob into a Python Byte string 
     */
    inline PyObject * pyAttributeToPyObject(SPL::blob & attr) {
      long int sizeb = attr.getSize();
      const unsigned char * bytes = attr.getData();

      return PyBytes_FromStringAndSize((const char *)bytes, sizeb);
    }

    /**
     * Convert a SPL rstring into a Python Unicode string 
     */
    inline PyObject * pyAttributeToPyObject(SPL::rstring & attr) {
      long int sizeb = attr.size();
      const char * pybytes = attr.data();

      return PyUnicode_DecodeUTF8(pybytes, sizeb, NULL);
    }
    
    class Splpy {
      public:
      /**
       * Load the C Python runtime and execute a setup
       * script splpy_setup.py at the given path.
      */
      static void loadCPython(const char* spl_setup_py) {
      	// If the Python runtime is being embedded in a shared library
      	// (as is the case with IBM Streams), there is a bug where the 
      	// symbols from libpython*.*.so are not resolved properly. As
      	// as workaround, it's necessary to manually rediscover the
      	// symbols by calling dlopen().
	if(NULL == dlopen("libpython3.5m.so", RTLD_LAZY |
			  RTLD_GLOBAL)){
	  SPLAPPLOG(L_ERROR, "Fatal error: could not open libpython3.5m.so", "python");
	  throw;
	}

       if (Py_IsInitialized() == 0) {
          Py_InitializeEx(0);
          PyEval_InitThreads();
          PyEval_SaveThread();
       }
    
       int fd = open(spl_setup_py, O_RDONLY);
       if (fd < 0) {
         SPLAPPTRC(L_ERROR,
            "Python script splpy_setup.py not found!:" << spl_setup_py,
                             "python");
           throw;   
       }
       PyGILLock lock;
       if (PyRun_SimpleFileEx(fdopen(fd, "r"), spl_setup_py, 1) != 0) {
         SPLAPPTRC(L_ERROR, "Python script splpy_setup.py failed!", "python");
         flush_PyErr_Print();
         throw;
       }
      }
    /*
    * Call PyErr_Print() and then flush stderr.
    * This is because CPython buffers stderr (and stdout)
    * when it is not connected to a terminal.
    * Without the flush output is lost in distributed
    * (since stderr is conncted to a file) and
    * makes diagnosing errors impossible.
    */
    static void flush_PyErr_Print() {
        PyErr_Print();
        PyRun_SimpleString("sys.stderr.flush()");
    }

    /*
     * Import a module, returning the reference to the module.
     * Caller must hold the GILState
     */
    static PyObject * importModule(const char * moduleNameC) 
    {
      PyObject * moduleName = PyUnicode_FromString(moduleNameC);
      PyObject * module = PyImport_Import(moduleName);
      Py_DECREF(moduleName);
      if (module == NULL) {
        SPLAPPLOG(L_ERROR, "Fatal error: missing module: " << moduleNameC, "python");
        flush_PyErr_Print();
        throw;
      }
      SPLAPPTRC(L_INFO, "Imported  module: " << moduleNameC, "python");
      return module;
    }

    /*
     * Load a function, returning the reference to the function.
     * Caller must hold the GILState
     */
    static PyObject * loadFunction(const char * moduleNameC, const char * functionNameC)
     {    
       PyObject * module = importModule(moduleNameC);
       PyObject * function = PyObject_GetAttrString(module, functionNameC);
       Py_DECREF(module);
    
       if (!PyCallable_Check(function)) {
           SPLAPPLOG(L_ERROR, "Fatal error: function " << functionNameC << " in module " << moduleNameC << " not callable", "python");
         throw;
        }
        SPLAPPTRC(L_INFO, "Callable function: " << functionNameC, "python");
        return function;
      }

    // Call the function passing an SPL attribute
    // converted to a Python object and discard the return 
    template <class T>
    static void pyTupleSink(PyObject * function, T & splVal) {
      PyGILLock lock;

      PyObject * arg = pyAttributeToPyObject(splVal);

      PyObject * pyReturnVar = pyTupleFunc(function, arg);

      if(pyReturnVar == 0){
        flush_PyErr_Print();
        throw;
      }

      Py_DECREF(pyReturnVar);
    }
    
    // prints a string representation of the PyObject for debugging purposes
    static void printPyObject(PyObject * pyObject) {
      PyObject* pyRepr = PyObject_Repr(pyObject);
      PyObject* pyStrBytes = PyUnicode_AsUTF8String(pyRepr);
      const char* s = PyBytes_AsString(pyStrBytes);
      std::cout << "pyObject=" << s << std::endl;
      Py_DECREF(pyStrBytes);
      Py_DECREF(pyRepr);
    }

    /*
    * Call a function passing the SPL attribute value of type T
    * and return the function return as a boolean
    */
    template <class T>
    static int pyTupleFilter(PyObject * function, T & splVal) {

      PyGILLock lock;

      PyObject * arg = pyAttributeToPyObject(splVal);

      PyObject * pyReturnVar = pyTupleFunc(function, arg);

      if(pyReturnVar == 0){
        flush_PyErr_Print();
        throw;
      }

      int ret = PyObject_IsTrue(pyReturnVar);

      Py_DECREF(pyReturnVar);
      return ret;
    }

    /*
    * Call a function passing the SPL attribute value of type T
    * and fill in the SPL attribute of type R with its result.
    */
    template <class T, class R>
    static int pyTupleTransform(PyObject * function, T & splVal, R & retSplVal) {
      PyGILLock lock;

      PyObject * arg = pyAttributeToPyObject(splVal);

      // invoke python nested function that calls the application function
      PyObject * pyReturnVar = pyTupleFunc(function, arg);

      if (pyReturnVar == Py_None){
        Py_DECREF(pyReturnVar);
        return 0;
      } else if(pyReturnVar == 0){
        flush_PyErr_Print();
        throw;
      } 

      pyAttributeFromPyObject(retSplVal, pyReturnVar);
      Py_DECREF(pyReturnVar);

      return 1;
    }

    // Python hash of an SPL attribute
    template <class T>
    static SPL::int32 pyTupleHash(PyObject * function, T & splVal) {

      PyGILLock lock;

      PyObject * arg = pyAttributeToPyObject(splVal);

      // invoke python nested function that generates the int32 hash
      PyObject * pyReturnVar = pyTupleFunc(function, arg); 
     
       // construct integer from  return value
      SPL::int32 retval=0;
      try {
      	retval = PyLong_AsLong(pyReturnVar);
      } catch(...) {
        Py_DECREF(pyReturnVar);
        flush_PyErr_Print();
        throw;
      }  	 
      Py_DECREF(pyReturnVar);		
      return retval;
   }

    /**
     * Call a Python function passing in the SPL tuple as 
     * the single element of a Python tuple.
     * Steals the reference to value.
    */
    static PyObject * pyTupleFunc(PyObject * function, PyObject * value) {
      PyObject * pyTuple = PyTuple_New(1);
      PyTuple_SetItem(pyTuple, 0, value);

      PyObject * pyReturnVar = PyObject_CallObject(function, pyTuple);
      Py_DECREF(pyTuple);

      return pyReturnVar;
    }

    };
   
  }
}
