import numpy as np

def cos_edge_square(twidth,  dt, ramp_fraction=0.25):
    tedge=np.arange(0, 2*twidth*ramp_fraction, dt)
    t=np.arange(0, twidth, dt)
    width=len(t)
    if (ramp_fraction>0 and ramp_fraction<=0.5 and twidth>0):
        f=1.0/(2*ramp_fraction*twidth)
        edges=(np.cos(2*np.pi*f*tedge-np.pi)+1.0)/2.0
        #pyplot.plot(edges)
        #pyplot.show()
        nramp=int(len(edges)/2)
        nflat=width-len(edges)
        env=np.concatenate((edges[:nramp], np.ones(nflat), edges[nramp:]))
    else:
        print('ramp_fraction (ramp_length/twidth) should be 0<ramp_function<=0.5, %s and twidth>0 %s'%(str(ramp_fraction), str(twidth)))
        env=np.ones(width)
    return (t, env)

def gaussian(twidth, dt, sigmas=3):
    """
    Width is the exact width, not the width of the sigmas.
    sigmas is the number of sigma in the width in each side
    """
    #print 'gaussian', twidth, dt, sigmas
    t=np.arange(0, twidth, dt)
    #print len(t), len(np.arange(0, 4e-6, 1e-9))
    width=len(t)
    sigma = width / (2.0 * sigmas)  # (width - 1 )?
    val=np.exp(-(np.arange(0, width) - width / 2.) ** 2. / (2 * sigma ** 2)).astype('complex64')
    #print twidth, dt, width, len(t), len(val)
    #print t, val
    return (t, val)