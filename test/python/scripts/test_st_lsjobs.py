# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2019
import unittest
import sys
import os
import time
import requests
import uuid
import glob
import re


from streamsx.topology.topology import Topology
from streamsx.topology.context import submit, ConfigParams
from streamsx.rest import Instance
import streamsx.scripts.streamtool as streamtool


from contextlib import contextmanager
from io import StringIO


import test_sc
@unittest.skipUnless(test_sc.cpd_setup(), "requires Streams REST API setup")
class Testlsjobs(unittest.TestCase):
    def _submitjob(self, args, sab=None):
        args.insert(0, "--disable-ssl-verify")
        args.insert(1, "submitjob")
        if sab:
            args.insert(2, sab)
        else:
            topo = Topology()
            topo.source([1])
            cfg = {}
            cfg[ConfigParams.SSL_VERIFY] = False
            src = submit("BUNDLE", topo, cfg)
            sab_path = src["bundlePath"]
            args.insert(2, sab_path)
            self.files_to_remove.append(sab_path)
        rc, job = streamtool.run_cmd(args=args)
        self.jobs_to_cancel.append(job)
        assert job is not None
        return rc, job

    def _ls_jobs(
        self,
        jobs=None,
        users=None,
        jobnames=None,
        fmt=None,
        xheaders=False,
        showtimestamp=False,
        long=False
    ):
        args = ["--disable-ssl-verify", "lsjobs"]
        if jobs:
            args.extend(["--jobs", jobs])
        if users:
            args.extend(["--users", users])
        if jobnames:
            args.extend(["--jobnames", jobnames])
        if fmt:
            args.extend(["--fmt", fmt])
        if xheaders:
            args.append("--xheaders")
        if showtimestamp:
            args.append("--showtimestamp")
        if long:
            args.append("--long")

        return streamtool.run_cmd(args=args)

    def setUp(self):
        self.instance = Instance.of_endpoint(verify=False).id
        self.username = os.environ["STREAMS_USERNAME"]
        self.stringLength = 10
        self.jobs_to_cancel = []
        self.files_to_remove = []
        self.my_instance = Instance.of_endpoint(username=self.username, verify=False)
        self.instance_string = "Instance: " + self.my_instance.id
        self.name = "TEST__" + uuid.uuid4().hex.upper()[0 : self.stringLength]
        self.true_headers = ["Id", "State", "Healthy", "User", "Date", "Name", "Group"]

    def tearDown(self):
        for job in self.jobs_to_cancel:
            job.cancel(force=True)

        self.files_to_remove.extend(glob.glob("./test_st_lsjobs.*.json"))

        for file in self.files_to_remove:
            if os.path.exists(file):
                os.remove(file)

    def split_string(self, my_string):
        """ Helper function that splits my_string by 2 or more whitespaces

        Arguments:
            my_string {String} -- [description]

        Returns:
            {List} -- List containing the elements of my_string that are seperated by 2 or more whitespaces
        """
        return re.split(r"\s{2,}", my_string.strip())

    def get_jobs_from_output(self, jobs, output):
        """ Get the string corresponding to each job in jobs from the lsjobs output string

        Arguments:
            jobs {List} -- List of job objects
            output {String} -- Strings, representing the output from lsjobs

        Returns:
            {List} -- Returns the list of strings corresponding to each job from jobs
        """
        job_outputs = []
        for job in jobs:
            job_line = None
            for line in output:
                if job.id in line and job.name in line:
                    job_line = line
                    break
            if job_line:
                job_outputs.append(job_line)
            else:
                self.fail("Job not found in output")
        return job_outputs

    ###########################################
    # Tf fmt
    ###########################################

    def check_job_Tf_fmt(self, job, job_to_check, long=False):
        """ Helper function that tests whether a single job outputed from lsjobs in Tf format is correct

        Arguments:
            job {Job_object} -- A job object as returned by _submitjob()
            job_to_check {String} -- A string of the form (given below) that represents a single job outputed from lsjobs in Tf format
            long {bool} -- Represents the optional arg that displays 'ProductVersion', if true, check this is value is correct
        """
        # Ex of job_to_check
        # 7  Running  yes     streamsadmin  2019-07-17T14:49:25-0700  KF_TEST_12345  default

        job_details = self.split_string(job_to_check)
        self.assertEqual(job.id, job_details[0])  # job ID
        self.assertEqual(self.username, job_details[3])  # job user
        self.assertEqual(job.name, job_details[5])  # job name
        self.assertEqual(job.jobGroup.split("/")[-1], job_details[6])  # job group
        # if long option, job_to_check contains an extra value, the productVersion
        if long:
            self.assertTrue(len(job_details) == 8)
            prod_version = job.productVersion
            self.assertEqual(prod_version, job_details[7])  # job productVersions
        else:
            self.assertTrue(len(job_details) == 7)

    # Create 2 jobs w/ names, check correct ouput in default Tf format
    def test_lsjobs_simple(self):
        rc, job1 = self._submitjob(args=["--jobname", self.name])
        rc, job2 = self._submitjob(args=["--jobname", self.name + self.name])
        output, error, rc = self.get_output(lambda: self._ls_jobs())

        # Check instance data output correctly
        self.assertEqual(output[0].strip(), self.instance_string)

        # Check headers outputs correctly
        headers = self.split_string(output[1])
        self.assertEqual(self.true_headers, headers)

        # Can't assume this is the only job running, find the line w/ our jobID, then do checks
        job_output = self.get_jobs_from_output([job1, job2], output)
        job1_output, job2_output = job_output[0], job_output[1]

        # Check details of each job are correct
        self.check_job_Tf_fmt(job1, job1_output)
        self.check_job_Tf_fmt(job2, job2_output)
        self.assertEqual(rc, 0)

    # Create 3 jobs w/ names, and check --jobnames option correctly returns desired jobnames
    def test_lsjobs_complex_1(self):
        rc, job1 = self._submitjob(args=["--jobname", self.name])
        rc, job2 = self._submitjob(args=["--jobname", self.name + self.name])
        rc, job3 = self._submitjob(
            args=["--jobname", self.name + self.name + self.name]
        )

        # Get jobs job1 and job3 via --jobnames option
        job_names = str(job1.name) + "," + str(job3.name)
        output, error, rc = self.get_output(lambda: self._ls_jobs(jobnames=job_names))

        # Check instance data output correctly
        self.assertEqual(output[0].strip(), self.instance_string)

        # Check headers outputs correctly
        headers = self.split_string(output[1])
        self.assertEqual(self.true_headers, headers)

        # Filtering by job_names but can't assume output order, thus find jobs then do checks
        job_output = self.get_jobs_from_output([job1, job3], output)
        job1_output, job3_output = job_output[0], job_output[1]

        # Check details of each job are correct
        self.check_job_Tf_fmt(job1, job1_output)
        self.check_job_Tf_fmt(job3, job3_output)
        self.assertEqual(rc, 0)

    # Create 3 jobs w/ names, and check --jobs option correctly returns desired jobnames
    def test_lsjobs_complex_2(self):
        rc, job1 = self._submitjob(args=["--jobname", self.name])
        rc, job2 = self._submitjob(args=["--jobname", self.name + self.name])
        rc, job3 = self._submitjob(
            args=["--jobname", self.name + self.name + self.name]
        )

        job_ids = str(job1.id) + "," + str(job3.id)
        output, error, rc = self.get_output(lambda: self._ls_jobs(jobs=job_ids))

        # Check instance data output correctly
        self.assertEqual(output[0].strip(), self.instance_string)

        # Check headers outputs correctly
        headers = self.split_string(output[1])
        self.assertEqual(self.true_headers, headers)

        # Filtering by jobs_ids but can't assume output order, thus find jobs then do checks
        job_output = self.get_jobs_from_output([job1, job3], output)
        job1_output, job3_output = job_output[0], job_output[1]

        # Check details of each job are correct
        self.check_job_Tf_fmt(job1, job1_output)
        self.check_job_Tf_fmt(job3, job3_output)
        self.assertEqual(rc, 0)

    # Create a single job, and check --xheaders option correctly removes all headers
    def test_lsjobs_simple_2(self):
        rc, job = self._submitjob(args=[])
        output, error, rc = self.get_output(lambda: self._ls_jobs(xheaders=True))

        # Can't assume this is the only job running, find the line w/ our jobID, then do checks
        job_output = self.get_jobs_from_output([job], output)[0]

        # Check details of job are correct
        self.check_job_Tf_fmt(job, job_output)
        self.assertEqual(rc, 0)

    # Create a single job, and check --showtimestamp option correctly shows the timestamp
    def test_lsjobs_simple_3(self):
        rc, job = self._submitjob(args=[])
        output, error, rc = self.get_output(lambda: self._ls_jobs(showtimestamp=True))

        # Check -- showtimestamp correctly outputs timestamp
        Date_string = "Date: "
        self.assertTrue(Date_string in output[0].strip())

        # Check instance data output correctly
        self.assertEqual(output[1].strip(), self.instance_string)

        # Check headers outputs correctly
        headers = self.split_string(output[2])
        self.assertEqual(self.true_headers, headers)

        # Can't assume this is the only job running, find the line w/ our jobID, then do checks
        job_output = self.get_jobs_from_output([job], output)[0]

        # Check details of job are correct
        self.check_job_Tf_fmt(job, job_output)
        self.assertEqual(rc, 0)

    # Create a single job, and check that if --xheaders and --showtimestamp option, --xheaders option still removes all headers
    def test_lsjobs_simple_4(self):
        rc, job = self._submitjob(args=[])
        job_ids = str(job.id)
        output, error, rc = self.get_output(
            lambda: self._ls_jobs(jobs=job_ids, xheaders=True, showtimestamp=True)
        )

        # len 1 bc only 1 job (since we are filtering by this jobID)
        self.assertTrue(len(output) == 1)

        # Check details of job are correct
        self.check_job_Tf_fmt(job, output[0])
        self.assertEqual(rc, 0)

    # Create a single job, and check that if --users w/ blank string (ie shouldn't be any users like this), it correctly returns no jobs
    def test_lsjobs_simple_5(self):
        rc, job = self._submitjob(args=[])
        output, error, rc = self.get_output(
            lambda: self._ls_jobs(users=' ')
        )

        # len 2 bc only contains instance string and header string
        self.assertTrue(len(output) == 2)

    # Test that --long option works
    def test_lsjobs_simple_long(self):
        rc, job = self._submitjob(args=[])
        output, error, rc = self.get_output(lambda: self._ls_jobs(long=True))

        # Check instance data output correctly
        self.assertEqual(output[0].strip(), self.instance_string)

        # Check headers outputs correctly
        self.true_headers.append("ProductVersion")
        headers = self.split_string(output[1])
        self.assertEqual(self.true_headers, headers)

        # Can't assume this is the only job running, find the line w/ our jobID, then do checks
        job_output = self.get_jobs_from_output([job], output)[0]

        # Check details of job are correct
        self.check_job_Tf_fmt(job, job_output, long=True)
        self.assertEqual(rc, 0)

    ###########################################
    # Mf fmt
    ###########################################

    def get_job_Mf_fmt(self, output, long=False):
        """ Helper function that gets a single job block outputed from lsjobs in Mf format

        Arguments:
            output {String} -- A string given by the ouput of lsjobs in Mf format
            long {bool} -- Represents the optional arg that displays 'ProductVersion', if true, need to get 1 extra row

        Returns:
            job_details {String} -- A string of the form (given below) that represents a single job outputed from lsjobs in Mf format
            output {String} -- A string given by the ouput of lsjobs in Mf format
        """
        # Ex of job_details
        # =================================================
        # Id              : 7
        # State           : Running
        # Healthy         : yes
        # User            : streamsadmin
        # Date            : 2019-07-17T14:49:25-0700
        # Name            : KF_TEST_12345
        # Group           : default
        # ProductVersion  : blahblah                           <--- This row only present if long is true
        # =================================================

        job_details = None
        if long:
            job_details = output[:10]
            output = output[9:]
        else:
            job_details = output[:9]
            output = output[8:]
        return job_details, output

    def check_job_Mf_fmt(self, job, job_to_check, long=False):
        """ Helper function that tests whether a single job outputed from lsjobs in Mf format is correct

        Arguments:
            job {Job_object} -- A job object as returned by _submitjob()
            job_to_check {String} -- A string of the form (given below) that represents a single job outputed from lsjobs in Mf format
            long {bool} -- Represents the optional arg that displays 'ProductVersion', if true, check this is value is correct 
        """
        # Ex of job_to_check
        # =================================================
        # Id              : 7
        # State           : Running
        # Healthy         : yes
        # User            : streamsadmin
        # Date            : 2019-07-17T14:49:25-0700
        # Name            : KF_TEST_12345
        # Group           : default
        # ProductVersion  : blahblah                           <--- This row only present if long is true
        # =================================================

        # Get details of job
        ids = self.split_string(job_to_check[1])
        states = self.split_string(job_to_check[2])
        healths = self.split_string(job_to_check[3])
        Users = self.split_string(job_to_check[4])
        Dates = self.split_string(job_to_check[5])
        Names = self.split_string(job_to_check[6])
        Groups = self.split_string(job_to_check[7])

        # Check headers
        headers = [
            ids[0],
            states[0],
            healths[0],
            Users[0],
            Dates[0],
            Names[0],
            Groups[0],
        ]

        # if long, need to check corresponding value is correct
        if long:
            self.true_headers.append('ProductVersion')
            prod_version = self.split_string(job_to_check[8])
            headers.append(prod_version[0])
            self.assertTrue(prod_version[1])

        self.assertEqual(self.true_headers, headers)

        # Check job details
        self.assertEqual(ids[2], job.id)
        self.assertEqual(Users[2], self.username)
        self.assertEqual(Names[2], job.name)
        self.assertEqual(Groups[2], job.jobGroup.split("/")[-1])

    # Create a job check correct ouput in Mf format
    def test_lsjobs_simple_Mf_fmt(self):
        rc, job1 = self._submitjob(args=["--jobname", self.name])
        job_ids = str(job1.id)
        output, error, rc = self.get_output(lambda: self._ls_jobs(jobs=job_ids, fmt="%Mf"))

        # Check instance data output correctly
        self.assertEqual(output[1].strip(), self.instance_string)

        # Remove instance data from output
        output = output[2:]

        # Check details of job1 are correct
        job_details1, output = self.get_job_Mf_fmt(output)
        self.check_job_Mf_fmt(job1, job_details1)
        self.assertEqual(rc, 0)

    # Test that --long option works
    def test_lsjobs_simple_Mf_fmt_long(self):
        rc, job = self._submitjob(args=[])
        job_ids = str(job.id)
        output, error, rc = self.get_output(lambda: self._ls_jobs(jobs=job_ids, fmt="%Mf", long=True))

        # Check instance data output correctly
        self.assertEqual(output[1].strip(), self.instance_string)

        # Remove instance data from output
        output = output[2:]

        # Check details of job are correct
        job_details, output = self.get_job_Mf_fmt(output, long=True)
        self.check_job_Mf_fmt(job, job_details, long=True)
        self.assertEqual(rc, 0)

    ###########################################
    # Nf fmt
    ###########################################

    def check_job_nf_fmt(self, job, job_to_check, long=False):
        """ Helper function that tests whether a single job outputed from lsjobs in Nf format is correct

        Arguments:
            job {Job_object} -- A job object as returned by _submitjob()
            job_to_check {String} -- A string of the form (given below) that represents a single job outputed from lsjobs in Nf format
            long {bool} -- Represents the optional arg that displays 'ProductVersion', if true, check this is value is correct

        """
        # Ex of job_to_check
        # Id: 7 State: Running Healthy: yes User: streamsadmin Date: 2019-07-17T14:49:25-0700 Name: KF_TEST_12345 Group: default

        # Check headers
        self.assertTrue("Id" in job_to_check)
        self.assertTrue("State" in job_to_check)
        self.assertTrue("Healthy" in job_to_check)
        self.assertTrue("User" in job_to_check)
        self.assertTrue("Date" in job_to_check)
        self.assertTrue("Name" in job_to_check)
        self.assertTrue("Group" in job_to_check)

        # if long, need to check its in the job
        if long:
            self.assertTrue("ProductVersion" in job_to_check)
            prod_version = job.productVersion
            self.assertTrue(prod_version in job_to_check)

        # Check job details
        self.assertTrue(job.id in job_to_check)  # ID
        self.assertTrue(self.username in job_to_check)  # user
        self.assertTrue(job.name in job_to_check)  # name
        self.assertTrue(job.jobGroup.split("/")[-1] in job_to_check)  # group

    # Create 2 jobs w/ names, check correct ouput in Nf format
    def test_lsjobs_simple_Nf_fmt(self):
        rc, job1 = self._submitjob(args=["--jobname", self.name])
        rc, job2 = self._submitjob(args=["--jobname", self.name + self.name])
        output, error, rc = self.get_output(lambda: self._ls_jobs(fmt="%Nf"))

        # Can't assume this is the only job running, find the line w/ our jobID, then do checks
        job_output = self.get_jobs_from_output([job1, job2], output)
        job1_output, job2_output = job_output[0], job_output[1]

        # Check details of each job are correct
        self.check_job_nf_fmt(job1, job1_output)
        self.check_job_nf_fmt(job2, job2_output)
        self.assertEqual(rc, 0)

    # Test that --long option works in Nf format
    def test_lsjobs_simple_Nf_fmt_long(self):
        rc, job = self._submitjob(args=[])
        output, error, rc = self.get_output(lambda: self._ls_jobs(fmt="%Nf", long=True))

        # Can't assume this is the only job running, find the line w/ our jobID, then do checks
        job_output = self.get_jobs_from_output([job], output)[0]

        # Check details of job are correct
        self.check_job_nf_fmt(job, job_output)
        self.assertEqual(rc, 0)

    def get_output(self, my_function):
        """ Helper function that gets the ouput from executing my_function

        Arguments:
            my_function {} -- The function to be executed

        Returns:
            stdout {List} -- list of lines from the output of my_function, split at line boundaries
            stderr {String} -- Errors and exceptions from executing my_function
            rc {int} -- 0 indicates succces, 1 indicates error or failure
        """
        rc = None
        with captured_output() as (out, err):
            rc, val = my_function()
        stdout = out.getvalue().strip()
        stderr = err.getvalue().strip()
        return stdout.splitlines(), stderr, rc


@contextmanager
def captured_output():
    new_out, new_err = StringIO(), StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    try:
        sys.stdout, sys.stderr = new_out, new_err
        yield sys.stdout, sys.stderr
    finally:
        sys.stdout, sys.stderr = old_out, old_err
